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
import com.culberth.tools.artemislab.run.RunManifest;
import com.culberth.tools.artemislab.run.RunStore;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.time.Instant;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Component;

/**
 * {@code LAB-SMOKE}: one owned anycast queue and a bounded, deterministic send. It exists to prove the write path end
 * to end — ownership recorded before creation, every connection verified, bounds enforced, a readiness assertion with a
 * deadline — before any P1 fixture relies on it.
 */
@Component
public class SmokeScenario
{

    public static final String ID = "LAB-SMOKE";

    private final RunStore store;
    private final LabLimits limits;

    public SmokeScenario(RunStore store, LabLimits limits)
    {
        this.store = store;
        this.limits = limits;
    }

    public static String queueName(String runId)
    {
        return "lab." + runId + ".smoke";
    }

    public String run(Job job, String runId, TargetGuard guard, int count, int bodyBytes) throws Exception
    {
        limits.checkCount(count);
        limits.checkBodyBytes(bodyBytes);
        RunManifest run = store.get(runId);
        long bytes = (long) count * bodyBytes;
        limits.checkRunBytes(run.generatedBytes(), bytes);
        String queue = queueName(runId);
        if (run.resources().stream().anyMatch(r -> r.name().equals(queue) && r.state().mayExist()))
        {
            throw new LabException("This run already has " + queue + ". Clean up the run or start a new one.");
        }

        try (Connection admin = guard.open();
                ManagementClient management = new ManagementClient(admin, limits.operationTimeout()))
        {
            createOwnedQueue(runId, management, queue);
            job.checkCancelled();
            String ids = send(job, guard, runId, queue, count, bodyBytes);
            store.update(runId, r -> r.withGeneratedBytes(r.generatedBytes() + bytes));
            return awaitCount(job, runId, management, queue, count) + " Sent " + count + " (" + ids + ").";
        }
    }

    /**
     * Records the queue and its address as PLANNED only once the broker is seen not to have either, then creates them.
     * A name that already exists is refused and never recorded as owned.
     */
    private void createOwnedQueue(String runId, ManagementClient management, String queue) throws JMSException
    {
        if (management.addressNames().contains(queue) || management.queueNames().contains(queue))
        {
            throw new LabException(queue + " already exists on the broker. The lab does not adopt resources it did not "
                    + "create; nothing was changed.");
        }
        store.update(runId, r -> r
                .withResource(
                        new OwnedResource(Kind.ADDRESS, queue, queue, "ANYCAST", State.PLANNED, "verified absent"))
                .withResource(
                        new OwnedResource(Kind.QUEUE, queue, queue, "ANYCAST", State.PLANNED, "verified absent")));
        String configuration = "{\"name\":\"" + queue + "\",\"address\":\"" + queue
                + "\",\"routing-type\":\"ANYCAST\",\"durable\":true,\"auto-create-address\":true}";
        management.invoke(ResourceNames.BROKER, "createQueue", configuration, false);
        boolean address = management.addressNames().contains(queue);
        boolean created = management.queueNames().contains(queue);
        store.update(runId, r -> r.withResources(res ->
        {
            if (!res.name().equals(queue))
            {
                return res;
            }
            boolean exists = res.kind() == Kind.QUEUE ? created : address;
            return exists ? res.with(State.CREATED, "created " + Instant.now())
                    : res.with(State.NOT_CREATED, "not present after createQueue");
        }));
        if (!created)
        {
            throw new LabException("createQueue succeeded but " + queue + " is not listed; stopped.");
        }
    }

    /** On a worker connection of its own, verified separately: one session, one thread. */
    private String send(Job job, TargetGuard guard, String runId, String queue, int count, int bodyBytes)
            throws JMSException
    {
        String first = null;
        String last = null;
        try (Connection worker = guard.open())
        {
            Session session = worker.createSession(false, Session.AUTO_ACKNOWLEDGE);
            try (MessageProducer producer = session.createProducer(session.createQueue(queue)))
            {
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                for (int i = 1; i <= count; i++)
                {
                    job.checkCancelled();
                    TextMessage message = session.createTextMessage(body(runId, i, bodyBytes));
                    message.setStringProperty("labRun", runId);
                    message.setIntProperty("labSeq", i);
                    producer.send(message);
                    if (first == null)
                    {
                        first = message.getJMSMessageID();
                    }
                    last = message.getJMSMessageID();
                    if (i % 100 == 0)
                    {
                        job.progress("sent " + i + " of " + count);
                    }
                }
            }
            finally
            {
                session.close();
            }
        }
        return "first " + first + ", last " + last;
    }

    /** The readiness predicate: the count the broker reports, not the sends that returned. */
    private String awaitCount(Job job, String runId, ManagementClient management, String queue, int expected)
            throws JMSException, InterruptedException
    {
        long deadline = System.nanoTime() + limits.readinessDeadline().toNanos();
        long seen = -1;
        while (System.nanoTime() < deadline)
        {
            job.checkCancelled();
            seen = management.messageCount(queue);
            if (seen == expected)
            {
                String text = "messageCount " + seen + " on " + queue + " at " + Instant.now() + " (expected "
                        + expected + ").";
                store.update(runId, r -> r
                        .withAction(ActionRecord.now(job.id(), "assert messageCount", Outcome.ASSERTION_PASSED, text)));
                return text;
            }
            Thread.sleep(200);
        }
        String text = "messageCount " + seen + " on " + queue + " after " + limits.readinessDeadline() + ", expected "
                + expected + ".";
        store.update(runId,
                r -> r.withAction(ActionRecord.now(job.id(), "assert messageCount", Outcome.ASSERTION_FAILED, text)));
        throw new LabException("Fixture assertion failed: " + text);
    }

    /** Deterministic: the same run, sequence and size always give the same body. */
    static String body(String runId, int sequence, int bytes)
    {
        String head = "lab smoke " + runId + " #" + sequence + " ";
        if (head.length() >= bytes)
        {
            return head.substring(0, bytes);
        }
        return head + "x".repeat(bytes - head.length());
    }
}
