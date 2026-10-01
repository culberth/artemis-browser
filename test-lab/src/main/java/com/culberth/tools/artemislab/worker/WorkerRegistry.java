package com.culberth.tools.artemislab.worker;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Every live worker, by run. Bounded by {@code lab.limits.max-live-workers} across the lab; a run's workers are stopped
 * before its cleanup and before the broker is stopped or restarted, so nothing writes to — or holds messages on — a
 * broker the lab is about to change.
 */
@Component
public class WorkerRegistry implements DisposableBean
{

    private static final int RETAINED_FINISHED = 100;

    private final int maxLive;
    private final Map<String, Worker> workers = new LinkedHashMap<>();

    public WorkerRegistry(LabLimits limits)
    {
        this.maxLive = limits.maxLiveWorkers();
    }

    public static String newId()
    {
        return "w" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Refuses before a connection is opened if starting {@code more} would pass the limit. */
    public synchronized void reserve(int more)
    {
        long live = workers.values().stream().filter(Worker::active).count();
        if (live + more > maxLive)
        {
            throw new LabException("That would make " + (live + more) + " live workers; the limit is " + maxLive
                    + " (lab.limits.max-live-workers). Stop some first.");
        }
    }

    public synchronized <W extends Worker> W add(W worker)
    {
        workers.put(worker.id(), worker);
        trim();
        return worker;
    }

    public synchronized List<Worker> of(String runId)
    {
        return workers.values().stream().filter(w -> w.runId().equals(runId)).toList();
    }

    public synchronized List<Worker> active(String runId)
    {
        return workers.values().stream().filter(w -> w.runId().equals(runId) && w.active()).toList();
    }

    public synchronized Optional<Worker> find(String runId, String workerId)
    {
        Worker worker = workers.get(workerId);
        return worker != null && worker.runId().equals(runId) ? Optional.of(worker) : Optional.empty();
    }

    /** Stops a run's live workers; returns one line per worker for the run history. */
    public List<String> stopAll(String runId, String why)
    {
        List<String> outcomes = new ArrayList<>();
        for (Worker worker : active(runId))
        {
            outcomes.add(worker.stop(why));
        }
        return outcomes;
    }

    /** Stops every live worker in the lab. */
    public List<String> stopEverything(String why)
    {
        List<Worker> live;
        synchronized (this)
        {
            live = workers.values().stream().filter(Worker::active).toList();
        }
        return live.stream().map(w -> w.stop(why)).toList();
    }

    private void trim()
    {
        var iterator = workers.values().iterator();
        long finished = workers.values().stream().filter(w -> !w.active()).count();
        while (finished > RETAINED_FINISHED && iterator.hasNext())
        {
            if (!iterator.next().active())
            {
                iterator.remove();
                finished--;
            }
        }
    }

    @Override
    public void destroy()
    {
        stopEverything("lab shutting down");
    }
}
