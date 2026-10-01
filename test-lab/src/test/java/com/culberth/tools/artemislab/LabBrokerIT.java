package com.culberth.tools.artemislab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemislab.broker.BrokerLauncher;
import com.culberth.tools.artemislab.broker.BrokerService;
import com.culberth.tools.artemislab.broker.LabBroker;
import com.culberth.tools.artemislab.broker.ManagementClient;
import com.culberth.tools.artemislab.broker.TargetGuard;
import com.culberth.tools.artemislab.job.Job;
import com.culberth.tools.artemislab.job.JobRunner;
import com.culberth.tools.artemislab.run.ActionRecord.Outcome;
import com.culberth.tools.artemislab.run.OwnedResource;
import com.culberth.tools.artemislab.run.RunManifest;
import com.culberth.tools.artemislab.run.RunManifest.RunState;
import com.culberth.tools.artemislab.run.RunService;
import com.culberth.tools.artemislab.run.RunStore;
import com.culberth.tools.artemislab.scenario.Fixture;
import com.culberth.tools.artemislab.scenario.SearchScenario;
import com.culberth.tools.artemislab.scenario.SmokeScenario;
import com.culberth.tools.artemislab.worker.Worker;
import com.culberth.tools.artemislab.worker.WorkerRegistry;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;

/**
 * The lab against a real broker from the supported matrix ({@code -Dartemis.image}, set per failsafe execution): it
 * provisions, identifies, writes only after verifying, refuses a wrong target without mutating anything, cleans up
 * exactly what it owns, refuses a taken port, and removes its container. The broker is published on a free port rather
 * than 62616, which this machine's hand-run test container may hold.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LabBrokerIT
{

    static final String IMAGE = System.getProperty("artemis.image", "apache/artemis:2.55.0-alpine");
    static final int PORT = freePort();

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("lab.data-dir", dataDir::toString);
        registry.add("lab.broker.port", () -> PORT);
        registry.add("lab.broker.images", () -> IMAGE);
    }

    @Autowired
    BrokerService brokers;
    @Autowired
    RunService runs;
    @Autowired
    RunStore store;
    @Autowired
    JobRunner runner;
    @Autowired
    SmokeScenario smoke;
    @Autowired
    BrokerLauncher launcher;
    @Autowired
    LabLimits limits;
    @Autowired
    WorkerRegistry workers;

    static LabBroker broker;
    static String runId;

    @AfterAll
    static void release(@Autowired BrokerService brokers)
    {
        if (brokers.current().isPresent())
        {
            brokers.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Provisioning starts the pinned image on loopback and records the identity two connections agree on")
    void provisions()
    {
        broker = brokers.provision(IMAGE);

        assertEquals("127.0.0.1", broker.host());
        assertEquals(PORT, broker.port());
        assertFalse(broker.nodeId().isBlank());
        String tag = IMAGE.substring(IMAGE.indexOf(':') + 1).replace("-alpine", "");
        assertTrue(broker.reportedVersion().startsWith(tag), broker.reportedVersion());
        assertThrows(LabException.class, () -> brokers.provision(IMAGE), "one broker at a time");
        assertTrue(launcher.leftovers().stream().noneMatch(l -> broker.brokerId().equals(l.brokerId())),
                "the lab's own broker must never be offered for removal as a leftover");
    }

    @Test
    @Order(2)
    @DisplayName("A guard expecting another broker refuses before any write: nothing is created, nothing recorded")
    void wrongTargetMutatesNothing() throws Exception
    {
        RunManifest run = runs.create("it");
        try (ActiveMQConnectionFactory factory = independentFactory())
        {
            TargetGuard wrong = new TargetGuard(factory, "artemis", "artemis", "not-this-broker",
                    TargetGuard.managementReader(Duration.ofSeconds(10)));
            Job job = runner.submit(run.runId(), JobRunner.newToken(), "wrong target", j ->
            {
                try (Fixture fixture = Fixture.open(j, run.runId(), wrong, wrong, store, limits, workers))
                {
                    return smoke.run(fixture, Map.of("count", 5, "bodyBytes", 10));
                }
            });
            runner.awaitIdle(run.runId(), Duration.ofSeconds(30));

            assertEquals(Job.State.FAILED, job.state());
            assertTrue(job.detail().startsWith("Refused"), job.detail());
            assertTrue(store.get(run.runId()).resources().isEmpty());
            try (Connection connection = factory.createConnection("artemis", "artemis"))
            {
                connection.start();
                try (ManagementClient management = new ManagementClient(connection, Duration.ofSeconds(10)))
                {
                    assertFalse(management.queueNames().contains(SmokeScenario.queueName(run.runId())));
                    assertFalse(management.addressNames().contains(SmokeScenario.queueName(run.runId())));
                }
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("LAB-SMOKE creates its owned queue, sends the bounded count and asserts it from the broker")
    void smokeScenario() throws Exception
    {
        runId = runs.create("it").runId();
        Job job = runs.runScenario(runId, SmokeScenario.ID, Map.of("count", "25", "bodyBytes", "200"),
                JobRunner.newToken());
        runner.awaitIdle(runId, Duration.ofSeconds(60));

        assertEquals(Job.State.SUCCEEDED, job.state(), job.detail());
        RunManifest run = store.get(runId);
        assertEquals(5000, run.generatedBytes());
        assertEquals(25, run.sent().size());
        assertEquals(2, run.resources().size());
        assertTrue(run.resources().stream().allMatch(r -> r.state() == OwnedResource.State.CREATED));
        assertTrue(run.history().stream().anyMatch(a -> a.outcome() == Outcome.ASSERTION_PASSED));
        assertEquals(25, independentCount(SmokeScenario.queueName(runId)));
    }

    @Test
    @Order(4)
    @DisplayName("BASIC, BODIES and SEARCH prepare exactly what they promise, checked independently of the lab")
    void p1Recipes() throws Exception
    {
        for (String id : List.of("BASIC", "BODIES", "SEARCH"))
        {
            Job job = runs.runScenario(runId, id, Map.of(), JobRunner.newToken());
            runner.awaitIdle(runId, Duration.ofSeconds(120));
            assertEquals(Job.State.SUCCEEDED, job.state(), id + ": " + job.detail());
        }
        String prefix = "lab." + runId + ".";
        assertEquals(0, independentCount(prefix + "empty"));
        assertEquals(251, independentCount(prefix + "paged"));
        assertEquals(1, independentCount(prefix + "odd name & ü 日本"));
        assertEquals(2, independentAttribute(prefix + "counters", "scheduledCount"));
        assertEquals(3, independentAttribute(prefix + "counters", "messagesAcknowledged"));
        assertEquals(9, independentCount(prefix + "counters"));

        List<Message> bodies = independentBrowse(prefix + "bodies", null);
        assertEquals(17, bodies.size());
        assertEquals("plain-text", bodies.get(0).getStringProperty("labCase"));
        assertEquals(250_000, ((TextMessage) bodies.get(11)).getText().length(), "the large text arrives whole");
        assertEquals(250_000L, ((BytesMessage) bodies.get(12)).getBodyLength());
        assertTrue(bodies.get(1) instanceof TextMessage text && text.getText().contains("<script>"));
        assertEquals("=1+1", ((TextMessage) bodies.get(13)).getText());

        List<Message> late = independentBrowse(prefix + "search-a", "marker = 'late'");
        assertEquals(10, late.size());
        assertEquals(251, late.get(0).getIntProperty("labSeq"));
        long nines = java.util.stream.IntStream.rangeClosed(1, 300).filter(seq -> SearchScenario.priority(seq) == 9)
                .count();
        assertEquals(nines, independentBrowse(prefix + "search-a", "JMSPriority = 9").size());
        List<Message> all = independentBrowse(prefix + "search-a", null);
        assertEquals(251, all.get(290).getIntProperty("labSeq"), "the late markers browse last, at 291-300");
        assertEquals(260, all.get(299).getIntProperty("labSeq"));
        assertEquals(4, independentBrowse(prefix + "search-b", "marker = 'late'").size());
        assertEquals(60, independentBrowse(prefix + "search-many", "marker = 'many'").size());
        assertEquals(4, independentBrowse(prefix + "export-bounds", "marker = 'deep'").size());

        RunManifest run = store.get(runId);
        assertEquals(25 + (1 + 251 + 12 + 1) + 17 + (300 + 20 + 60 + 10), run.sent().size());
        assertEquals(14, run.sent().stream().filter(m -> m.note().equals("marker=late")).count());
        assertEquals(2 * (1 + 5 + 1 + 4), run.resources().size(), "queue and address for each of 11 queues");
        Job again = runs.runScenario(runId, "BODIES", Map.of(), JobRunner.newToken());
        runner.awaitIdle(runId, Duration.ofSeconds(30));
        assertEquals(Job.State.FAILED, again.state(), "a recipe whose queues the run already owns is refused");
        assertTrue(again.detail().contains("already has"), again.detail());
        assertEquals(17, independentCount(prefix + "bodies"), "and nothing more was sent");
    }

    @Test
    @Order(5)
    @DisplayName("DELIVERY, SUBSCRIPTIONS and RATES: held, redelivered, expired, routed and rated, checked independently")
    void workerRecipes() throws Exception
    {
        for (String id : List.of("DELIVERY", "SUBSCRIPTIONS", "RATES"))
        {
            Job job = runs.runScenario(runId, id, Map.of(), JobRunner.newToken());
            runner.awaitIdle(runId, Duration.ofSeconds(180));
            assertEquals(Job.State.SUCCEEDED, job.state(), id + ": " + job.detail());
        }
        String p = "lab." + runId + ".";

        // DELIVERY: held stays held across jobs, and is browse-invisible.
        assertEquals(10, independentAttribute(p + "held", "deliveringCount"));
        assertEquals(250, independentAttribute(p + "all-held", "deliveringCount"));
        assertEquals(0, independentBrowse(p + "all-held", null).size(), "held messages are not browsable");
        assertEquals(3, independentAttribute(p + "sched", "scheduledCount"));
        assertEquals(1, independentAttribute(p + "kill-absent", "messagesKilled"));
        assertEquals(4, independentCount(p + "expiry.exp"));

        // Roll back the first redelivery message to its limit: the third attempt dead-letters it.
        List<String> outcomes = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            outcomes.add(step("DELIVERY", "rollback", Map.of()).detail());
        }
        assertTrue(outcomes.get(2).contains("attempt 3"), outcomes.toString());
        assertEquals(2, independentCount(p + "redelivery"));
        assertEquals(1, independentCount(p + "redelivery.dlq"));
        assertEquals(1, independentAttribute(p + "redelivery", "messagesKilled"));

        // Acknowledge the holder of 10, release the holder of 250.
        Worker holder = worker("holder of 10");
        Worker all = worker("holder of all 250");
        assertEquals(Job.State.SUCCEEDED, act(holder, "acknowledge").state());
        assertEquals(Job.State.SUCCEEDED, act(all, "stop").state());
        assertEquals(10, waitFor(p + "held", "messageCount", 10));
        assertEquals(0, independentAttribute(p + "held", "deliveringCount"));
        assertEquals(250, independentBrowse(p + "all-held", null).size(), "released messages are browsable again");

        // SUBSCRIPTIONS
        assertEquals(6, independentCount(p + "orders-q"));
        assertEquals(10, independentCount("lab-" + runId + ".durable-all"));
        assertEquals(5, independentAttribute("lab-" + runId + ".durable-eu", "messagesAcknowledged"));
        assertEquals(3, independentCount(p + "routed.eu"));
        assertEquals(3, independentCount(p + "routed.audit"));
        assertEquals(6, independentBrowse("lab." + runId + ".feed::" + p + "feed-sub-a", null).size(),
                "a multicast queue reads through its FQQN");

        // RATES: traffic does what it reports, then the queue is recreated under a new id.
        assertEquals(4, independentAttribute(p + "clients", "consumerCount"));
        step("RATES", "produce", Map.of("perSecond", "50", "seconds", "2"));
        Worker producer = worker("produce to");
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (producer.active() && System.nanoTime() < deadline)
        {
            Thread.sleep(100);
        }
        assertFalse(producer.active(), "the producer stops by itself");
        assertTrue(producer.sent() >= 80 && producer.sent() <= 100, "about 50/s for 2s: " + producer.sent());
        assertEquals(producer.sent(), independentCount(p + "rate"));
        long before = independentAttribute(p + "rate", "ID");
        assertTrue(step("RATES", "recreate", Map.of()).detail().contains("Recreated"));
        assertTrue(independentAttribute(p + "rate", "ID") != before, "a recreated queue has a new id");
        assertEquals(0, independentAttribute(p + "rate", "messagesAdded"));
        assertTrue(runs.workers(runId).stream().filter(Worker::active).count() >= 5, "clients, holders, live sub");
    }

    @Test
    @Order(6)
    @DisplayName("BEHAVIOR and PRESSURE: configured queues and full addresses, and their steps, checked independently")
    void behaviorAndPressure() throws Exception
    {
        for (String id : List.of("BEHAVIOR", "PRESSURE"))
        {
            Job job = runs.runScenario(runId, id, Map.of(), JobRunner.newToken());
            runner.awaitIdle(runId, Duration.ofSeconds(180));
            assertEquals(Job.State.SUCCEEDED, job.state(), id + ": " + job.detail());
        }
        String p = "lab." + runId + ".";

        assertEquals(1, independentCount(p + "lvq"));
        assertEquals(3, independentCount(p + "ring"));
        assertEquals(3, independentCount(p + "keep"));
        assertEquals(3, independentAttribute(p + "purge", "messagesKilled"));
        assertEquals(20, independentAttribute(p + "exclusive", "deliveringCount"));
        assertEquals(0, independentAttribute(p + "gated", "deliveringCount"));
        assertEquals(Boolean.TRUE, independentQueueValue(p + "paused", "paused"));

        step("BEHAVIOR", "resume", Map.of());
        assertEquals(Boolean.FALSE, independentQueueValue(p + "paused", "paused"));
        step("BEHAVIOR", "gate", Map.of());
        assertEquals(3, waitFor(p + "gated", "deliveringCount", 3), "dispatch starts at two consumers");

        assertEquals(40, independentCount(p + "page"));
        assertTrue(independentCount(p + "fail") < 40);
        assertTrue(independentCount(p + "block") < 40);
        assertEquals(Boolean.TRUE, independentAddressValue(p + "blocked", "blockedViaManagement"));
        step("PRESSURE", "unblock", Map.of());
        assertEquals(Boolean.FALSE, independentAddressValue(p + "blocked", "blockedViaManagement"));
    }

    @Test
    @Order(8)
    @DisplayName("A restart stops every worker, keeps durable messages, and the broker comes back as the same node")
    void restart() throws Exception
    {
        Job job = runs.restartBroker(JobRunner.newToken());
        runner.awaitIdle(JobRunner.BROKER_SCOPE, Duration.ofSeconds(180));

        assertEquals(Job.State.SUCCEEDED, job.state(), job.detail());
        assertEquals(broker.nodeId(), brokers.current().orElseThrow().nodeId());
        assertTrue(runs.workers(runId).stream().noneMatch(Worker::active));
        assertEquals(10, independentCount("lab-" + runId + ".durable-all"), "durable messages survive a restart");
        assertEquals(20, waitFor("lab." + runId + ".imbalance", "messageCount", 20), "held messages came back");
    }

    @Test
    @Order(9)
    @DisplayName("Cleanup removes exactly the manifest's queue and address and proves them gone")
    void cleanup() throws Exception
    {
        Job job = runs.cleanup(runId, JobRunner.newToken());
        runner.awaitIdle(runId, Duration.ofSeconds(60));

        assertEquals(Job.State.SUCCEEDED, job.state(), job.detail());
        RunManifest run = store.get(runId);
        assertEquals(RunState.CLEANED, run.state());
        assertTrue(run.resources().stream().allMatch(r -> r.state() == OwnedResource.State.DELETED));
        try (ActiveMQConnectionFactory factory = independentFactory();
                Connection connection = factory.createConnection("artemis", "artemis"))
        {
            connection.start();
            try (ManagementClient management = new ManagementClient(connection, Duration.ofSeconds(10)))
            {
                String prefix = "lab." + runId + ".";
                String clientPrefix = "lab-" + runId;
                assertTrue(
                        management.queueNames().stream().noneMatch(
                                n -> n.startsWith(prefix) || n.startsWith(clientPrefix) || n.contains(runId)),
                        "queues left: " + management.queueNames());
                assertTrue(management.addressNames().stream().noneMatch(n -> n.startsWith(prefix)),
                        "addresses left: " + management.addressNames());
                assertTrue(management.divertNames().stream().noneMatch(n -> n.startsWith(prefix)),
                        "diverts left: " + management.divertNames());
            }
        }
        assertTrue(runs.workers(runId).stream().noneMatch(Worker::active), "cleanup stops the workers");
    }

    @Test
    @Order(11)
    @DisplayName("A taken port is refused, not moved")
    void takenPortRefused() throws Exception
    {
        try (ServerSocket holder = new ServerSocket())
        {
            holder.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            LabException refused = assertThrows(LabException.class, () -> launcher.launch("bport", IMAGE,
                    holder.getLocalPort(), "artemis", "artemis", Duration.ofMinutes(1)));
            assertTrue(refused.getMessage().contains("already in use"), refused.getMessage());
        }
    }

    @Test
    @Order(12)
    @DisplayName("Stopping the broker removes its container")
    void stopRemovesContainer() throws Exception
    {
        Job job = runs.stopBroker(JobRunner.newToken());
        runner.awaitIdle(JobRunner.BROKER_SCOPE, Duration.ofSeconds(60));

        assertEquals(Job.State.SUCCEEDED, job.state(), job.detail());
        assertTrue(brokers.current().isEmpty());
        List<?> remaining = DockerClientFactory.instance().client().listContainersCmd().withShowAll(true)
                .withLabelFilter(java.util.Map.of(BrokerLauncher.BROKER_LABEL, broker.brokerId())).exec();
        assertTrue(remaining.isEmpty(), "container still present: " + remaining);
    }

    private Job step(String scenario, String stepId, Map<String, String> form) throws InterruptedException
    {
        Job job = runs.runStep(runId, scenario, stepId, form, JobRunner.newToken());
        runner.awaitIdle(runId, Duration.ofSeconds(60));
        assertEquals(Job.State.SUCCEEDED, job.state(), scenario + " " + stepId + ": " + job.detail());
        return job;
    }

    private Job act(Worker worker, String action) throws InterruptedException
    {
        Job job = runs.workerAction(runId, worker.id(), action, JobRunner.newToken());
        runner.awaitIdle(runId, Duration.ofSeconds(60));
        return job;
    }

    private Worker worker(String descriptionStart)
    {
        return runs.workers(runId).stream().filter(w -> w.description().startsWith(descriptionStart))
                .reduce((first, second) -> second).orElseThrow();
    }

    /** Polls a queue attribute until it reads {@code expected} or ten seconds pass; returns the last reading. */
    private long waitFor(String queue, String attribute, long expected) throws Exception
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        long seen;
        do
        {
            seen = independentAttribute(queue, attribute);
            if (seen == expected)
            {
                return seen;
            }
            Thread.sleep(200);
        }
        while (System.nanoTime() < deadline);
        return seen;
    }

    private ActiveMQConnectionFactory independentFactory()
    {
        return new ActiveMQConnectionFactory("tcp://127.0.0.1:" + PORT + "?useTopologyForLoadBalancing=false");
    }

    private Object independentQueueValue(String queue, String attribute) throws Exception
    {
        return independentValue(org.apache.activemq.artemis.api.core.management.ResourceNames.QUEUE + queue, attribute);
    }

    private Object independentAddressValue(String address, String attribute) throws Exception
    {
        return independentValue(org.apache.activemq.artemis.api.core.management.ResourceNames.ADDRESS + address,
                attribute);
    }

    private Object independentValue(String resource, String attribute) throws Exception
    {
        try (ActiveMQConnectionFactory factory = independentFactory();
                Connection connection = factory.createConnection("artemis", "artemis"))
        {
            connection.start();
            try (ManagementClient management = new ManagementClient(connection, Duration.ofSeconds(10)))
            {
                return management.attribute(resource, attribute);
            }
        }
    }

    private long independentAttribute(String queue, String attribute) throws Exception
    {
        try (ActiveMQConnectionFactory factory = independentFactory();
                Connection connection = factory.createConnection("artemis", "artemis"))
        {
            connection.start();
            try (ManagementClient management = new ManagementClient(connection, Duration.ofSeconds(10)))
            {
                return management.queueAttribute(queue, attribute);
            }
        }
    }

    /** A JMS QueueBrowser over the whole queue, in order; the messages stay readable after the connection closes. */
    private List<Message> independentBrowse(String queue, String selector) throws Exception
    {
        List<Message> messages = new ArrayList<>();
        try (ActiveMQConnectionFactory factory = independentFactory();
                Connection connection = factory.createConnection("artemis", "artemis"))
        {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            try (QueueBrowser browser = session.createBrowser(session.createQueue(queue), selector))
            {
                Enumeration<?> e = browser.getEnumeration();
                while (e.hasMoreElements())
                {
                    Message message = (Message) e.nextElement();
                    // Read bodies while connected: an Artemis large message may stream its body on first access.
                    if (message instanceof TextMessage text)
                    {
                        text.getText();
                    }
                    else if (message instanceof BytesMessage bytes)
                    {
                        bytes.readBytes(new byte[(int) bytes.getBodyLength()]);
                        bytes.reset();
                    }
                    messages.add(message);
                }
            }
        }
        return messages;
    }

    private long independentCount(String queue) throws Exception
    {
        try (ActiveMQConnectionFactory factory = independentFactory();
                Connection connection = factory.createConnection("artemis", "artemis"))
        {
            connection.start();
            try (ManagementClient management = new ManagementClient(connection, Duration.ofSeconds(10)))
            {
                return management.messageCount(queue);
            }
        }
    }

    private static int freePort()
    {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress()))
        {
            return socket.getLocalPort();
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
