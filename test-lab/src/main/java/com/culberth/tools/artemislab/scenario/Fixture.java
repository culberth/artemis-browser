package com.culberth.tools.artemislab.scenario;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import com.culberth.tools.artemislab.broker.BrokerProfile;
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
import com.culberth.tools.artemislab.worker.BrowsingClient;
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
    private final BrokerProfile profile;
    private final java.util.function.Function<String, TargetGuard> userGuards;
    private final Connection admin;
    private final ManagementClient management;

    private Fixture(Job job, String runId, TargetGuard guard, TargetGuard holdingGuard, RunStore store,
            LabLimits limits, WorkerRegistry workers, BrokerProfile profile,
            java.util.function.Function<String, TargetGuard> userGuards, Connection admin, ManagementClient management)
    {
        this.profile = profile;
        this.userGuards = userGuards;
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
        return open(job, runId, guard, holdingGuard, store, limits, workers, BrokerProfile.STANDARD, user ->
        {
            throw new LabException("This broker has no test user " + user + ".");
        });
    }

    /**
     * @param profile    how the broker was configured at startup
     * @param userGuards a guard connecting as one of the profile's test users
     */
    public static Fixture open(Job job, String runId, TargetGuard guard, TargetGuard holdingGuard, RunStore store,
            LabLimits limits, WorkerRegistry workers, BrokerProfile profile,
            java.util.function.Function<String, TargetGuard> userGuards) throws JMSException
    {
        Connection admin = guard.open();
        try
        {
            return new Fixture(job, runId, guard, holdingGuard, store, limits, workers, profile, userGuards, admin,
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

    public BrokerProfile profile()
    {
        return profile;
    }

    /** A verified connection as one of the profile's test users; the caller closes it. */
    public Connection connectionAs(String user) throws JMSException
    {
        return userGuards.apply(user).open();
    }

    /** Whether any of this run's traffic workers is still sending or receiving. */
    public boolean trafficRunning()
    {
        return workers.active(runId).stream().anyMatch(w -> w instanceof Traffic);
    }

    /** Every queue this run created and still owns, by exact name. */
    public List<String> ownedQueues()
    {
        return store.get(runId).resources().stream().filter(r -> r.kind() == Kind.QUEUE && r.state() == State.CREATED)
                .map(OwnedResource::name).toList();
    }

    /** Reads these attributes of every owned queue. */
    public Map<String, Map<String, Long>> readQueues(List<String> attributes) throws JMSException
    {
        Map<String, Map<String, Long>> reading = new java.util.TreeMap<>();
        for (String queue : ownedQueues())
        {
            job.checkCancelled();
            Map<String, Long> values = new LinkedHashMap<>();
            for (String attribute : attributes)
            {
                values.put(attribute, management.queueAttribute(queue, attribute));
            }
            reading.put(queue, values);
        }
        return reading;
    }

    public void saveBaseline(com.culberth.tools.artemislab.run.RunManifest.Reading reading)
    {
        store.update(runId, r -> r.withBaseline(reading));
    }

    public com.culberth.tools.artemislab.run.RunManifest.Reading baseline()
    {
        return store.get(runId).baseline();
    }

    /** Records an automatic assertion's outcome, passed or failed; a failure throws. */
    public String assertThat(boolean held, String text)
    {
        return held ? passed(text) : failed(text);
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
        createQueue(name, address, routingType, Map.of());
    }

    /**
     * An owned durable anycast queue on an address of the same name, with queue configuration in the broker's
     * {@code createQueue} JSON keys, e.g. {@code last-value-key}, {@code ring-size}, {@code non-destructive},
     * {@code exclusive}, {@code purge-on-no-consumers}, {@code consumers-before-dispatch}.
     */
    public void createQueue(String name, Map<String, Object> queueConfiguration) throws JMSException
    {
        createQueue(name, name, "ANYCAST", queueConfiguration);
    }

    private void createQueue(String name, String address, String routingType, Map<String, Object> extra)
            throws JMSException
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
        configuration.putAll(extra);
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
        return openClient(description, clientId, queue, role, sessions, false);
    }

    /**
     * As {@link #openClient(String, String, String, OpenClient.Role, int)}; {@code buffered} consumers use the default
     * 1MB consumer window, so the broker dispatches into their buffers and the messages show as delivering to them —
     * how an exclusive or grouped queue's distribution becomes visible without anything being received.
     */
    public OpenClient openClient(String description, String clientId, String queue, OpenClient.Role role, int sessions,
            boolean buffered) throws JMSException
    {
        workers.reserve(1);
        TargetGuard chosen = buffered ? guard : holdingGuard;
        OpenClient client = workers.add(OpenClient.open(WorkerRegistry.newId(), runId, description, clientId,
                chosen.open(clientId), queue, role, sessions));
        recordWorker("started " + description);
        return client;
    }

    /** A browse-only client held open on a queue, registered as a worker of this run. */
    public BrowsingClient browse(String description, String queue) throws JMSException
    {
        workers.reserve(1);
        BrowsingClient client = workers
                .add(BrowsingClient.open(WorkerRegistry.newId(), runId, description, guard.open(), queue));
        recordWorker("started " + description);
        return client;
    }

    /**
     * Sends {@code count} bodies from a thread of its own and gives up after {@code wait}: under the BLOCK policy a
     * send waits for credit that never comes, so the connection is closed under it. Returns how many sends the broker
     * accepted; those are added to the send manifest.
     */
    public int sendBlocking(String queue, int count, int bodyBytes, Duration wait) throws Exception
    {
        limits.checkCount(count);
        limits.checkBodyBytes(bodyBytes);
        reserve((long) count * bodyBytes);
        Connection connection = guard.open();
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<SentMessage> sent = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread sender = new Thread(() ->
        {
            try
            {
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                jakarta.jms.MessageProducer producer = session.createProducer(session.createQueue(queue));
                for (int i = 1; i <= count; i++)
                {
                    jakarta.jms.TextMessage message = session.createTextMessage("x".repeat(bodyBytes));
                    message.setStringProperty("labRun", runId);
                    message.setIntProperty("labSeq", i);
                    producer.send(message, jakarta.jms.DeliveryMode.PERSISTENT, 4, 0);
                    sent.add(new SentMessage(queue, i, "text", message.getJMSMessageID(), bodyBytes, "accepted"));
                    accepted.incrementAndGet();
                }
            }
            catch (JMSException | RuntimeException e)
            {
                // Blocked and then cut off, or refused: what the broker accepted is what is counted.
            }
        }, "lab-blocking-sender");
        sender.setDaemon(true);
        sender.start();
        sender.join(wait.toMillis());
        try
        {
            connection.close();
        }
        catch (JMSException ignored)
        {
            // Closing under a blocked send may complain; the broker side is what is measured.
        }
        sender.join(limits.operationTimeout().toMillis());
        recordSent(List.copyOf(sent));
        record("blocking send", queue + ": " + accepted.get() + " of " + count + " accepted before giving up after "
                + wait.toSeconds() + "s");
        return accepted.get();
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
