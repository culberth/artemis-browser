package com.culberth.tools.artemislab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemislab.broker.BrokerProfile;
import com.culberth.tools.artemislab.broker.BrokerService;
import com.culberth.tools.artemislab.broker.LabBroker;
import com.culberth.tools.artemislab.job.Job;
import com.culberth.tools.artemislab.job.JobRunner;
import com.culberth.tools.artemislab.run.RunManifest.RunState;
import com.culberth.tools.artemislab.run.RunService;
import com.culberth.tools.artemislab.run.RunStore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Each non-standard broker profile against a real broker: it starts with its configuration in place, the recipe that
 * needs it passes, and a broker started from a replacement entrypoint survives an interrupt as the same node.
 */
@SpringBootTest
class LabProfilesIT
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

    @AfterEach
    void stopBroker()
    {
        if (brokers.current().isPresent())
        {
            brokers.stop();
        }
    }

    @Test
    @DisplayName("Restricted users: viewer and nomanage are refused what the profile says; an interrupt keeps the node")
    void restricted() throws Exception
    {
        LabBroker broker = brokers.provision(IMAGE, BrokerProfile.RESTRICTED);
        String runId = runs.create("it").runId();

        Job failures = runScenario(runId, "FAILURES");
        assertEquals(Job.State.SUCCEEDED, failures.state(), failures.detail());

        Job interrupt = runs.interruptBroker(5, JobRunner.newToken());
        runner.awaitIdle(JobRunner.BROKER_SCOPE, Duration.ofSeconds(180));
        assertEquals(Job.State.SUCCEEDED, interrupt.state(), interrupt.detail());
        assertEquals(broker.nodeId(), brokers.current().orElseThrow().nodeId());
        assertEquals(BrokerProfile.RESTRICTED, brokers.current().orElseThrow().profile());

        // After the interrupt the profile's configuration is still the one in force: viewer is still refused.
        try (jakarta.jms.Connection connection = brokers.guardAs(brokers.current().orElseThrow(), "viewer").open();
                ManagementClientProbe viewer = new ManagementClientProbe(connection))
        {
            LabException denied = org.junit.jupiter.api.Assertions.assertThrows(LabException.class,
                    () -> viewer.client().invoke("broker", "getAcceptorsAsJSON"));
            assertTrue(denied.getMessage().contains("AMQ229032"), denied.getMessage());
        }
        Job cleanup = runs.cleanup(runId, JobRunner.newToken());
        runner.awaitIdle(runId, Duration.ofSeconds(60));
        assertEquals(Job.State.SUCCEEDED, cleanup.state(), cleanup.detail());
        assertEquals(RunState.CLEANED, store.get(runId).state());
    }

    @Test
    @DisplayName("Low global memory: a little traffic is most of a 2MB global limit")
    void lowMemory() throws Exception
    {
        brokers.provision(IMAGE, BrokerProfile.LOW_MEMORY);
        String runId = runs.create("it").runId();
        Job job = runScenario(runId, "LOW-LIMITS");
        assertEquals(Job.State.SUCCEEDED, job.state(), job.detail());
        assertTrue(job.detail().contains("global-max-size of 2097152"), job.detail());
    }

    @Test
    @DisplayName("Disk threshold reached: disk usage reads above a 1% threshold without anything filling the disk")
    void diskFull() throws Exception
    {
        brokers.provision(IMAGE, BrokerProfile.DISK_FULL);
        String runId = runs.create("it").runId();
        Job job = runScenario(runId, "LOW-LIMITS");
        assertEquals(Job.State.SUCCEEDED, job.state(), job.detail());
        assertTrue(job.detail().contains("max-disk-usage of 1%"), job.detail());
    }

    @Test
    @DisplayName("A recipe that needs a profile is refused on a broker without it, before anything runs")
    void profileRequired() throws Exception
    {
        brokers.provision(IMAGE);
        String runId = runs.create("it").runId();
        LabException refused = org.junit.jupiter.api.Assertions.assertThrows(LabException.class,
                () -> runs.runScenario(runId, "FAILURES", Map.of(), JobRunner.newToken()));
        assertTrue(refused.getMessage().contains("Restricted users"), refused.getMessage());
        assertTrue(store.get(runId).resources().isEmpty());
    }

    /** A management client on a connection the test closes itself. */
    private record ManagementClientProbe(com.culberth.tools.artemislab.broker.ManagementClient client)
            implements AutoCloseable
    {
        ManagementClientProbe(jakarta.jms.Connection connection) throws jakarta.jms.JMSException
        {
            this(new com.culberth.tools.artemislab.broker.ManagementClient(connection, Duration.ofSeconds(10)));
        }

        @Override
        public void close() throws jakarta.jms.JMSException
        {
            client.close();
        }
    }

    private Job runScenario(String runId, String id) throws InterruptedException
    {
        Job job = runs.runScenario(runId, id, Map.of(), JobRunner.newToken());
        runner.awaitIdle(runId, Duration.ofSeconds(120));
        return job;
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
