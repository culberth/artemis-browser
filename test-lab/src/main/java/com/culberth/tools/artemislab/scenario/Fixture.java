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
import com.culberth.tools.artemislab.worker.HeldConsumer;
import com.culberth.tools.artemislab.worker.OpenClient;
import com.culberth.tools.artemislab.worker.Traffic;
import com.culberth.tools.artemislab.worker.WorkerRegistry;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a recipe builds with, for one job in one run: owned resources, guarded senders, one-shot consumption and
 * rollback, workers that outlive the job, and readiness assertions that read the broker rather than trusting the sends.
 *
 * <p>
 * Every connection — the admin one opened here, each sender's and each worker's — comes from a {@link TargetGuard}, so
 * a recipe cannot reach a broker the lab does not own. Resources are recorded PLANNED only after the broker is seen not
 * to have them, then CREATED once listed; a name that already exists is refused and never adopted.
 */
public final class Fixture implements AutoCloseable
{

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A JMS action that creates something, run after its resources are recorded PLANNED. */
    @FunctionalInterface
    public interface Creation
    {
        void run() throws JMSException;
    }

    private final Job job;
    private final String runId;
    private final TargetGuard guard;
    private final TargetGuard holdingGuard;
    private final RunStore store;
    private final LabLimits limits;
    private final WorkerRegistry workers;
    private final Connection admin;
    private final ManagementClient management;

    private Fixture(Job job, String runId, TargetGuard guard, TargetGuard holdingGuard, RunStore store,
            LabLimits limits, WorkerRegistry workers, Connection admin, ManagementClient management)
    {
        this.job = job;
        this.runId = runId;
        this.guard = guard;
        this.holdingGuard = holdingGuard;
        this.store = store;
        this.limits = limits;
        this.workers = workers;
        this.admin = admin;
        this.management = management;
    }

    /**
     * @param holdingGuard connections with {@code consumerWindowSize=0}, for consumers that must hold exactly what they
     *                     received
     */
    public static Fixture open(Job job, String runId, TargetGuard guard, TargetGuard holdingGuard, RunStore store,
            LabLimits limits, WorkerRegistry workers) throws JMSException
    {
        Connection admin = guard.open();
        try
        {
            return new Fixture(job, runId, guard, holdingGuard, store, limits, workers, admin,
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

    /** A JMS client id for this run: {@code lab-<runId>-<suffix>}. */
    public String clientId(String suffix)
    {
        return "lab-" + runId + "-" + suffix;
    }

    /** Refuses up front if the recipe's planned bytes would take the run past its budget. */
    public void reserve(long plannedBytes)
    {
        limits.checkRunBytes(store.get(runId).generatedBytes(), plannedBytes);
    }

    /** Refuses up front if the recipe's workers would pass the live-worker limit. */
    public void workers(int planned)
    {
        workers.reserve(planned);
    }

    /** Stops this run's traffic workers, leaving held consumers and idle clients alone. */
    public String stopTraffic()
    {
        List<String> stopped = workers.active(runId).stream().filter(w -> w instanceof Traffic)
                .map(w -> w.stop("stopped by step")).toList();
        stopped.forEach(line -> record("worker", line));
        return stopped.isEmpty() ? "No traffic was running." : String.join(" ", stopped);
    }

    /**
     * Destroys an owned queue and creates it again under the same name and address: its counters restart and its id
     * changes, which is the discontinuity a rate must not be computed across.
     */
    public String recreateQueue(String queue) throws JMSException
    {
        var owned = store.get(runId).resources().stream()
                .filter(r -> r.kind() == Kind.QUEUE && r.name().equals(queue) && r.state() == State.CREATED).findFirst()
                .orElseThrow(() -> new LabException(queue + " is not a queue this run created."));
        Object before = management.attribute(ResourceNames.QUEUE + queue, "ID");
        management.invoke(ResourceNames.BROKER, "destroyQueue", queue, true, false);
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("name", queue);
        configuration.put("address", owned.address());
        configuration.put("routing-type", owned.routingType());
        configuration.put("durable", true);
        configuration.put("auto-create-address", false);
        management.invoke(ResourceNames.BROKER, "createQueue", JSON.writeValueAsString(configuration), false);
        Object after = management.attribute(ResourceNames.QUEUE + queue, "ID");
        String outcome = "Recreated " + queue + ": id " + before + " -> " + after + ".";
        store.update(runId, r -> r.withResource(owned.with(State.CREATED, outcome)));
        record("recreate queue", outcome);
        return outcome;
    }

    /** Refuses a recipe whose resources this run already owns: run it again in a new run. */
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

    /** Refuses a step whose recipe has not prepared this run yet. */
    public void requireOwned(String name, String recipe)
    {
        if (!store.get(runId).owns(name))
        {
            throw new LabException("Run " + recipe + " in this run first: it has no " + name + ".");
        }
    }

    /** An owned durable anycast queue on an address of the same name. */
    public void createQueue(String name) throws JMSException
    {
        createQueue(name, name, "ANYCAST");
    }

    /**
     * An owned durable queue. The address is created with it and owned too unless this run already owns it; an address
     * that exists and is not this run's is refused.
     */
    public void createQueue(String name, String address, String routingType) throws JMSException
    {
        boolean newAddress = !store.get(runId).owns(address);
        List<OwnedResource> planned = newAddress
                ? List.of(resource(Kind.ADDRESS, address, address, routingType),
                        resource(Kind.QUEUE, name, address, routingType))
                : List.of(resource(Kind.QUEUE, name, address, routingType));
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("name", name);
        configuration.put("address", address);
        configuration.put("routing-type", routingType);
        configuration.put("durable", true);
        configuration.put("auto-create-address", newAddress);
        own(planned, () -> management.invoke(ResourceNames.BROKER, "createQueue",
                JSON.writeValueAsString(configuration), false));
    }

    /** An owned address with no queue. */
    public void createAddress(String name, String routingType) throws JMSException
    {
        own(List.of(resource(Kind.ADDRESS, name, name, routingType)),
                () -> management.invoke(ResourceNames.BROKER, "createAddress", name, routingType));
    }

    /**
     * An owned divert on an owned address.
     *
     * @param routingType PASS, STRIP, ANYCAST or MULTICAST
     */
    public void createDivert(String name, String address, String forwardingAddress, boolean exclusive, String filter,
            String routingType) throws JMSException
    {
        OwnedResource divert = new OwnedResource(Kind.DIVERT, name, address, forwardingAddress, State.PLANNED,
                (exclusive ? "exclusive" : "non-exclusive") + (filter == null ? "" : ", filter " + filter));
        own(List.of(divert), () -> management.invoke(ResourceNames.BROKER, "createDivert", name, name, address,
                forwardingAddress, exclusive, filter, null, routingType));
    }

    /**
     * Address settings for exactly one owned address — never a wildcard — so removing them in cleanup restores what
     * applied before. Keys are the broker's JSON names, e.g. {@code maxDeliveryAttempts}, {@code deadLetterAddress}.
     */
    public void addAddressSettings(String address, Map<String, Object> settings) throws JMSException
    {
        if (address.contains("#") || address.contains("*") || !address.startsWith(name("")))
        {
            throw new LabException(
                    "Address settings only for one of this run's addresses, never a wildcard: " + address);
        }
        String json = JSON.writeValueAsString(settings);
        OwnedResource resource = new OwnedResource(Kind.ADDRESS_SETTINGS, address, address, "", State.PLANNED, json);
        store.update(runId, r -> r.withResource(resource));
        management.invoke(ResourceNames.BROKER, "addAddressSettings", address, json);
        store.update(runId, r -> r.withResource(resource.with(State.CREATED, json)));
    }

    /**
     * A queue that a JMS call creates — a durable or shared subscription — owned like any other: recorded PLANNED once
     * the broker is seen not to have it, then CREATED once listed.
     */
    public void createSubscriptionQueue(String queue, String address, Creation creation) throws JMSException
    {
        own(List.of(resource(Kind.QUEUE, queue, address, "MULTICAST")), creation);
    }

    /** A sender on a connection of its own, verified separately. Close it to flush the send manifest. */
    public Sender sender() throws JMSException
    {
        return new Sender(this, guard.open());
    }

    /** A verified connection with a client id set before it was used; the caller closes it. */
    public Connection connection(String clientId) throws JMSException
    {
        return guard.open(clientId);
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
     * Receives the next message in a transaction and rolls it back — one failed delivery attempt. Returns its
     * {@code JMSXDeliveryCount} on that attempt, or 0 if the queue had nothing to deliver.
     */
    public int rollbackOnce(String queue) throws JMSException
    {
        try (Connection connection = holdingGuard.open())
        {
            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            try (MessageConsumer consumer = session.createConsumer(session.createQueue(queue)))
            {
                Message message = consumer.receive(limits.operationTimeout().toMillis());
                if (message == null)
                {
                    return 0;
                }
                int attempt = message.getIntProperty("JMSXDeliveryCount");
                session.rollback();
                return attempt;
            }
            finally
            {
                session.close();
            }
        }
    }

    /**
     * Starts a consumer that receives {@code hold} messages and keeps them unacknowledged, registered as a worker of
     * this run. {@code hold} 0 leaves an open consumer holding nothing.
     */
    public HeldConsumer hold(String description, String clientId, String queue, String selector, int hold)
            throws JMSException
    {
        return holdOn(description, clientId, queue, false, selector, hold);
    }

    /** As {@link #hold}, on a topic: a live non-durable subscription that exists only while the worker does. */
    public HeldConsumer subscribeLive(String description, String clientId, String address, String selector)
            throws JMSException
    {
        return holdOn(description, clientId, address, true, selector, 0);
    }

    private HeldConsumer holdOn(String description, String clientId, String destination, boolean topic, String selector,
            int hold) throws JMSException
    {
        job.checkCancelled();
        workers.reserve(1);
        Connection connection = holdingGuard.open(clientId);
        Destination target;
        try
        {
            Session probe = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            target = topic ? probe.createTopic(destination) : probe.createQueue(destination);
            probe.close();
        }
        catch (JMSException | RuntimeException e)
        {
            connection.close();
            throw e;
        }
        HeldConsumer consumer = workers.add(
                HeldConsumer.open(WorkerRegistry.newId(), runId, description, clientId, connection, target, selector));
        if (hold > 0)
        {
            int got = consumer.hold(hold, limits.operationTimeout().toMillis());
            if (got != hold)
            {
                consumer.stop("received " + got + " of " + hold);
                throw new LabException(description + " received only " + got + " of " + hold + "; stopped it.");
            }
        }
        recordWorker("started " + description + (hold > 0 ? ", holding " + hold : ""));
        return consumer;
    }

    /** An idle identified or anonymous client, registered as a worker of this run. */
    public OpenClient openClient(String description, String clientId, String queue, OpenClient.Role role, int sessions)
            throws JMSException
    {
        workers.reserve(1);
        OpenClient client = workers.add(OpenClient.open(WorkerRegistry.newId(), runId, description, clientId,
                holdingGuard.open(clientId), queue, role, sessions));
        recordWorker("started " + description);
        return client;
    }

    /** Bounded traffic on its own thread, registered as a worker of this run. */
    public Traffic traffic(Traffic.Direction direction, String queue, int perSecond, Duration duration, int bodyBytes)
            throws JMSException
    {
        if (perSecond < 1 || perSecond > limits.maxRate())
        {
            throw new LabException(
                    "Rate must be between 1 and " + limits.maxRate() + " per second; got " + perSecond + ".");
        }
        if (duration.isNegative() || duration.isZero() || duration.compareTo(limits.maxTraffic()) > 0)
        {
            throw new LabException("Traffic may run at most " + limits.maxTraffic() + "; asked for " + duration + ".");
        }
        long maxMessages = Math.min(limits.maxMessages(), (long) perSecond * duration.toSeconds());
        if (direction == Traffic.Direction.PRODUCE)
        {
            reserve(maxMessages * bodyBytes);
        }
        workers.reserve(1);
        Traffic traffic = workers.add(Traffic.start(WorkerRegistry.newId(), runId, direction, guard.open(), queue,
                perSecond, duration, maxMessages, bodyBytes,
                bytes -> store.update(runId, r -> r.withGeneratedBytes(r.generatedBytes() + bytes))));
        recordWorker("started " + traffic.description() + ", at most " + maxMessages + " messages");
        return traffic;
    }

    /**
     * Waits until each queue attribute reads its expected value — e.g. {@code messageCount}, {@code scheduledCount},
     * {@code deliveringCount}, {@code messagesAdded}, {@code messagesAcknowledged}, {@code messagesExpired},
     * {@code messagesKilled}, {@code consumerCount} — or the readiness deadline passes. Recorded either way.
     */
    public String await(String queue, Map<String, Long> expected) throws JMSException, InterruptedException
    {
        return await(queue, expected, limits.readinessDeadline());
    }

    /** As {@link #await(String, Map)} with a longer deadline, for what the broker does on a timer (expiry scans). */
    public String await(String queue, Map<String, Long> expected, Duration deadline)
            throws JMSException, InterruptedException
    {
        return awaitResource(ResourceNames.QUEUE + queue, queue, expected, deadline);
    }

    /** As {@link #await(String, Map)} for an address's attributes, e.g. {@code unRoutedMessageCount}. */
    public String awaitAddress(String address, Map<String, Long> expected) throws JMSException, InterruptedException
    {
        return awaitResource(ResourceNames.ADDRESS + address, address, expected, limits.readinessDeadline());
    }

    private String awaitResource(String resource, String label, Map<String, Long> expected, Duration deadline)
            throws JMSException, InterruptedException
    {
        long until = System.nanoTime() + deadline.toNanos();
        Map<String, Long> seen = new LinkedHashMap<>();
        while (true)
        {
            job.checkCancelled();
            seen.clear();
            for (String attribute : expected.keySet())
            {
                seen.put(attribute, number(management.attribute(resource, attribute), label, attribute));
            }
            if (seen.equals(expected))
            {
                return passed(label + ": " + describe(seen));
            }
            if (System.nanoTime() > until)
            {
                return failed(
                        label + ": " + describe(seen) + ", expected " + describe(expected) + " within " + deadline);
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

    /** Records a step or worker event in the run's history. */
    public void record(String action, String detail)
    {
        store.update(runId, r -> r.withAction(ActionRecord.now(job.id(), action, Outcome.SUCCEEDED, detail)));
    }

    void recordSent(List<SentMessage> sent)
    {
        if (!sent.isEmpty())
        {
            store.update(runId, r -> r.withSent(sent));
        }
    }

    private void recordWorker(String detail)
    {
        record("worker", detail);
    }

    /**
     * Records {@code planned} PLANNED once the broker is seen not to have any of them, runs the creation, then records
     * each CREATED or NOT_CREATED by what the broker lists.
     */
    private void own(List<OwnedResource> planned, Creation creation) throws JMSException
    {
        job.checkCancelled();
        for (OwnedResource resource : planned)
        {
            if (exists(resource))
            {
                throw new LabException(resource.name() + " already exists on the broker. The lab does not adopt "
                        + "resources it did not create; nothing was changed.");
            }
        }
        store.update(runId, r ->
        {
            var next = r;
            for (OwnedResource resource : planned)
            {
                next = next.withResource(resource);
            }
            return next;
        });
        creation.run();
        String when = "created " + Instant.now();
        for (OwnedResource resource : planned)
        {
            boolean present = exists(resource);
            store.update(runId, r -> r.withResource(present ? resource.with(State.CREATED, when)
                    : resource.with(State.NOT_CREATED, "not listed after creation")));
            if (!present)
            {
                throw new LabException(resource.kind() + " " + resource.name()
                        + " is not listed after creation; stopped." + boundTo(resource));
            }
        }
    }

    /**
     * For a queue that did not appear: what is actually bound to its address, so a name the broker chose differently (a
     * subscription queue escapes dots) shows up in the error rather than as a silent leftover.
     */
    private String boundTo(OwnedResource resource)
    {
        if (resource.kind() != Kind.QUEUE)
        {
            return "";
        }
        try
        {
            Object names = management.attribute(ResourceNames.ADDRESS + resource.address(), "queueNames");
            return " Queues on " + resource.address() + ": "
                    + (names instanceof Object[] array ? java.util.Arrays.toString(array) : String.valueOf(names))
                    + ".";
        }
        catch (JMSException | RuntimeException e)
        {
            return "";
        }
    }

    /** Whether the broker lists a resource of this kind and name. */
    public boolean exists(OwnedResource resource) throws JMSException
    {
        return switch (resource.kind())
        {
            case QUEUE -> management.queueNames().contains(resource.name());
            case ADDRESS -> management.addressNames().contains(resource.name());
            case DIVERT -> divertNames().contains(resource.name());
            case ADDRESS_SETTINGS -> false;
        };
    }

    private Set<String> divertNames() throws JMSException
    {
        Object value = management.attribute(ResourceNames.BROKER, "divertNames");
        if (value instanceof Object[] array)
        {
            return java.util.Arrays.stream(array).map(String::valueOf).collect(Collectors.toSet());
        }
        throw new LabException("Unexpected divertNames from the broker: " + value);
    }

    private static OwnedResource resource(Kind kind, String name, String address, String routingType)
    {
        return new OwnedResource(kind, name, address, routingType, State.PLANNED, "verified absent");
    }

    private static long number(Object value, String label, String attribute)
    {
        if (value instanceof Number n)
        {
            return n.longValue();
        }
        throw new LabException("Unexpected " + attribute + " for " + label + ": " + value);
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
