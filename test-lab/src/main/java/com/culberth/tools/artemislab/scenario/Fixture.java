package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import com.culberth.tools.artemislab.broker.ManagementClient;
import com.culberth.tools.artemislab.broker.TargetGuard;
import com.culberth.tools.artemislab.job.Job;
import com.culberth.tools.artemislab.run.ActionRecord;
import com.culberth.tools.artemislab.run.ActionRecord.Outcome;
import com.culberth.tools.artemislab.run.OwnedResource;
import com.culberth.tools.artemislab.run.OwnedResource.Kind;
import com.culberth.tools.artemislab.run.OwnedResource.State;
import com.culberth.tools.artemislab.run.RunStore;
import com.culberth.tools.artemislab.run.SentMessage;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a recipe builds with, for one job in one run: owned queue creation, guarded senders, one-shot consumption, and
 * readiness assertions that read the broker rather than trusting the sends.
 *
 * <p>
 * Every connection — the admin one opened here and each sender's — comes from {@link TargetGuard}, so a recipe cannot
 * reach a broker the lab does not own. Resources are recorded PLANNED only after the broker is seen not to have them,
 * then CREATED once listed; a name that already exists is refused and never adopted.
 */
public final class Fixture implements AutoCloseable
{

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Job job;
    private final String runId;
    private final TargetGuard guard;
    private final RunStore store;
    private final LabLimits limits;
    private final Connection admin;
    private final ManagementClient management;

    private Fixture(Job job, String runId, TargetGuard guard, RunStore store, LabLimits limits, Connection admin,
            ManagementClient management)
    {
        this.job = job;
        this.runId = runId;
        this.guard = guard;
        this.store = store;
        this.limits = limits;
        this.admin = admin;
        this.management = management;
    }

    public static Fixture open(Job job, String runId, TargetGuard guard, RunStore store, LabLimits limits)
            throws JMSException
    {
        Connection admin = guard.open();
        try
        {
            return new Fixture(job, runId, guard, store, limits, admin,
                    new ManagementClient(admin, limits.operationTimeout()));
        }
        catch (JMSException | RuntimeException e)
        {
            admin.close();
            throw e;
        }
    }

    public Job job()
    {
        return job;
    }

    public String runId()
    {
        return runId;
    }

    public LabLimits limits()
    {
        return limits;
    }

    public ManagementClient management()
    {
        return management;
    }

    /** {@code lab.<runId>.<suffix>}. */
    public String name(String suffix)
    {
        return "lab." + runId + "." + suffix;
    }

    /** Refuses up front if the recipe's planned bytes would take the run past its budget. */
    public void reserve(long plannedBytes)
    {
        limits.checkRunBytes(store.get(runId).generatedBytes(), plannedBytes);
    }

    /** Refuses a recipe whose queues this run already owns: run it again in a new run. */
    public void requireNew(List<String> names)
    {
        var run = store.get(runId);
        for (String name : names)
        {
            if (run.owns(name))
            {
                throw new LabException("This run already has " + name + ". Clean up the run or start a new one.");
            }
        }
    }

    /** An owned durable anycast queue on an address of the same name. */
    public void createQueue(String name) throws JMSException
    {
        job.checkCancelled();
        if (management.addressNames().contains(name) || management.queueNames().contains(name))
        {
            throw new LabException(name + " already exists on the broker. The lab does not adopt resources it did not "
                    + "create; nothing was changed.");
        }
        store.update(runId, r -> r
                .withResource(new OwnedResource(Kind.ADDRESS, name, name, "ANYCAST", State.PLANNED, "verified absent"))
                .withResource(new OwnedResource(Kind.QUEUE, name, name, "ANYCAST", State.PLANNED, "verified absent")));
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("name", name);
        configuration.put("address", name);
        configuration.put("routing-type", "ANYCAST");
        configuration.put("durable", true);
        configuration.put("auto-create-address", true);
        management.invoke(ResourceNames.BROKER, "createQueue", JSON.writeValueAsString(configuration), false);
        boolean address = management.addressNames().contains(name);
        boolean created = management.queueNames().contains(name);
        String when = "created " + Instant.now();
        store.update(runId, r -> r.withResources(res ->
        {
            if (!res.name().equals(name))
            {
                return res;
            }
            boolean exists = res.kind() == Kind.QUEUE ? created : address;
            return exists ? res.with(State.CREATED, when)
                    : res.with(State.NOT_CREATED, "not present after createQueue");
        }));
        if (!created)
        {
            throw new LabException("createQueue succeeded but " + name + " is not listed; stopped.");
        }
    }

    /** A sender on a connection of its own, verified separately. Close it to flush the send manifest. */
    public Sender sender() throws JMSException
    {
        return new Sender(this, guard.open());
    }

    /** Receives and acknowledges {@code count} messages, then closes the consumer. Returns how many it got. */
    public int consume(String queue, int count) throws JMSException
    {
        try (Connection connection = guard.open())
        {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            try (MessageConsumer consumer = session.createConsumer(session.createQueue(queue)))
            {
                int received = 0;
                while (received < count)
                {
                    job.checkCancelled();
                    if (consumer.receive(limits.operationTimeout().toMillis()) == null)
                    {
                        break;
                    }
                    received++;
                }
                return received;
            }
            finally
            {
                session.close();
            }
        }
    }

    /**
     * Waits until each queue attribute reads its expected value — {@code messageCount}, {@code scheduledCount},
     * {@code messagesAdded}, {@code messagesAcknowledged} — or the readiness deadline passes. Recorded either way.
     */
    public String await(String queue, Map<String, Long> expected) throws JMSException, InterruptedException
    {
        long deadline = System.nanoTime() + limits.readinessDeadline().toNanos();
        Map<String, Long> seen = new LinkedHashMap<>();
        while (true)
        {
            job.checkCancelled();
            seen.clear();
            for (String attribute : expected.keySet())
            {
                seen.put(attribute, management.queueAttribute(queue, attribute));
            }
            if (seen.equals(expected))
            {
                return passed(queue + ": " + describe(seen));
            }
            if (System.nanoTime() > deadline)
            {
                return failed(queue + ": " + describe(seen) + ", expected " + describe(expected) + " within "
                        + limits.readinessDeadline());
            }
            Thread.sleep(200);
        }
    }

    /**
     * Counts the messages a JMS {@code QueueBrowser} with this selector returns — the whole queue, unlike a filtered
     * {@code countMessages}, which only looks at the first 200. Waits for the expected count or the deadline.
     */
    public String awaitSelected(String queue, String selector, int expected) throws JMSException, InterruptedException
    {
        long deadline = System.nanoTime() + limits.readinessDeadline().toNanos();
        try (Connection connection = guard.open())
        {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            try
            {
                while (true)
                {
                    job.checkCancelled();
                    int seen = 0;
                    try (QueueBrowser browser = session.createBrowser(session.createQueue(queue), selector))
                    {
                        Enumeration<?> messages = browser.getEnumeration();
                        while (messages.hasMoreElements())
                        {
                            messages.nextElement();
                            seen++;
                        }
                    }
                    String what = queue + " [" + selector + "]: " + seen;
                    if (seen == expected)
                    {
                        return passed(what);
                    }
                    if (System.nanoTime() > deadline)
                    {
                        return failed(what + ", expected " + expected + " within " + limits.readinessDeadline());
                    }
                    Thread.sleep(200);
                }
            }
            finally
            {
                session.close();
            }
        }
    }

    void recordSent(List<SentMessage> sent)
    {
        if (!sent.isEmpty())
        {
            store.update(runId, r -> r.withSent(sent));
        }
    }

    private String passed(String text)
    {
        store.update(runId, r -> r.withAction(ActionRecord.now(job.id(), "assert", Outcome.ASSERTION_PASSED, text)));
        return text;
    }

    private String failed(String text)
    {
        store.update(runId, r -> r.withAction(ActionRecord.now(job.id(), "assert", Outcome.ASSERTION_FAILED, text)));
        throw new LabException("Fixture assertion failed: " + text);
    }

    private static String describe(Map<String, Long> values)
    {
        return values.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
    }

    @Override
    public void close() throws JMSException
    {
        try
        {
            management.close();
        }
        finally
        {
            admin.close();
        }
    }
}
