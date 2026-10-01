package com.culberth.tools.artemislab.job;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Runs lab work in the background, bounded and serialised.
 *
 * <ul>
 * <li><b>Bounded</b>: at most {@link LabLimits#maxWorkers()} jobs at once; beyond that a request is refused, not
 * queued.</li>
 * <li><b>Serialised</b>: one job per scope (a run id) at a time, and broker lifecycle work ({@link #BROKER_SCOPE}) runs
 * only when nothing else does — a broker is never stopped under a scenario that is writing to it.</li>
 * <li><b>Idempotent</b>: every form carries a one-time token; a second submission with the same token returns the job
 * the first one started instead of starting another, so a double click never sends a fixture twice.</li>
 * </ul>
 */
@Component
public class JobRunner implements DisposableBean
{

    public static final String BROKER_SCOPE = "broker";

    private static final int RETAINED_JOBS = 200;
    private static final int RETAINED_TOKENS = 1000;

    /** The work itself. Returns a one-line outcome for the page; throws to fail. */
    @FunctionalInterface
    public interface Work
    {
        String run(Job job) throws Exception;
    }

    private final int maxWorkers;
    private final ExecutorService executor;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, String> tokens = boundedMap(RETAINED_TOKENS);

    public JobRunner(LabLimits limits)
    {
        this.maxWorkers = limits.maxWorkers();
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(maxWorkers, runnable ->
        {
            Thread thread = new Thread(runnable, "lab-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** A fresh one-time token for a form. */
    public static String newToken()
    {
        return UUID.randomUUID().toString();
    }

    public synchronized Job submit(String scope, String token, String description, Work work)
    {
        if (token == null || token.isBlank())
        {
            throw new LabException("The form had no submission token. Reload the page and try again.");
        }
        String existing = tokens.get(token);
        if (existing != null)
        {
            Job earlier = jobs.get(existing);
            if (earlier != null)
            {
                return earlier;
            }
            throw new LabException("That form was already submitted. Reload the page to act again.");
        }
        refuseConflicts(scope);
        long running = jobs.values().stream().filter(Job::active).count();
        if (running >= maxWorkers)
        {
            throw new LabException("The lab is already running " + running
                    + " jobs, its limit (lab.limits.max-workers). " + "Wait for one to finish.");
        }
        Job job = new Job("j" + UUID.randomUUID().toString().substring(0, 8), scope, description);
        jobs.put(job.id(), job);
        tokens.put(token, job.id());
        trim();
        job.attach(executor.submit(() -> execute(job, work)));
        return job;
    }

    public synchronized Optional<Job> find(String id)
    {
        return Optional.ofNullable(jobs.get(id));
    }

    /** Newest first. */
    public synchronized List<Job> jobs(String scope)
    {
        List<Job> matching = new ArrayList<>(jobs.values().stream().filter(j -> j.scope().equals(scope)).toList());
        Collections.reverse(matching);
        return matching;
    }

    public synchronized boolean active(String scope)
    {
        return jobs.values().stream().anyMatch(j -> j.active() && j.scope().equals(scope));
    }

    public synchronized boolean anyActive()
    {
        return jobs.values().stream().anyMatch(Job::active);
    }

    public void cancel(String jobId)
    {
        find(jobId).ifPresent(Job::requestCancel);
    }

    public void cancelAll(String scope)
    {
        List<Job> matching;
        synchronized (this)
        {
            matching = jobs.values().stream().filter(j -> j.active() && j.scope().equals(scope)).toList();
        }
        matching.forEach(Job::requestCancel);
    }

    /** Waits, bounded, until nothing is running in the scope. True if it got there. */
    public boolean awaitIdle(String scope, Duration deadline) throws InterruptedException
    {
        long until = System.nanoTime() + deadline.toNanos();
        while (active(scope))
        {
            if (System.nanoTime() > until)
            {
                return false;
            }
            Thread.sleep(50);
        }
        return true;
    }

    private void refuseConflicts(String scope)
    {
        for (Job job : jobs.values())
        {
            if (!job.active())
            {
                continue;
            }
            if (job.scope().equals(scope) || BROKER_SCOPE.equals(scope) || BROKER_SCOPE.equals(job.scope()))
            {
                throw new LabException(
                        "Refused: \"" + job.description() + "\" is still running. Wait for it or cancel it.");
            }
        }
    }

    private void execute(Job job, Work work)
    {
        try
        {
            job.finish(Job.State.SUCCEEDED, work.run(job));
        }
        catch (CancellationException | InterruptedException e)
        {
            job.finish(Job.State.CANCELLED, "Cancelled.");
        }
        catch (Exception e)
        {
            job.finish(job.cancelRequested() ? Job.State.CANCELLED : Job.State.FAILED,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private synchronized void trim()
    {
        var iterator = jobs.values().iterator();
        while (jobs.size() > RETAINED_JOBS && iterator.hasNext())
        {
            if (!iterator.next().active())
            {
                iterator.remove();
            }
        }
    }

    @Override
    public void destroy()
    {
        synchronized (this)
        {
            jobs.values().forEach(Job::requestCancel);
        }
        executor.shutdownNow();
    }

    private static <K, V> Map<K, V> boundedMap(int max)
    {
        return new LinkedHashMap<>()
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest)
            {
                return size() > max;
            }
        };
    }
}
