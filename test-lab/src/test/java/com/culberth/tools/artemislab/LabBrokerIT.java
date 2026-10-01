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
import com.culberth.tools.artemislab.scenario.SmokeScenario;
import jakarta.jms.Connection;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
            Job job = runner.submit(run.runId(), JobRunner.newToken(), "wrong target",
                    j -> smoke.run(j, run.runId(), wrong, 5, 10));
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
        Job job = runs.runScenario(runId, SmokeScenario.ID, 25, 200, JobRunner.newToken());
        runner.awaitIdle(runId, Duration.ofSeconds(60));

        assertEquals(Job.State.SUCCEEDED, job.state(), job.detail());
        RunManifest run = store.get(runId);
        assertEquals(5000, run.generatedBytes());
        assertEquals(2, run.resources().size());
        assertTrue(run.resources().stream().allMatch(r -> r.state() == OwnedResource.State.CREATED));
        assertTrue(run.history().stream().anyMatch(a -> a.outcome() == Outcome.ASSERTION_PASSED));
        assertEquals(25, independentCount(SmokeScenario.queueName(runId)));
    }

    @Test
    @Order(4)
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
                assertFalse(management.queueNames().contains(SmokeScenario.queueName(runId)));
                assertFalse(management.addressNames().contains(SmokeScenario.queueName(runId)));
            }
        }
    }

    @Test
    @Order(5)
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
    @Order(6)
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

    private ActiveMQConnectionFactory independentFactory()
    {
        return new ActiveMQConnectionFactory("tcp://127.0.0.1:" + PORT + "?useTopologyForLoadBalancing=false");
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
