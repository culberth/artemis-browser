package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * A broker that answers some reads and refuses others, and what each page makes of that.
 *
 * <p>
 * Per-operation refusal only exists with {@code management-message-rbac} switched on — by default {@code manage} is all
 * or nothing — so this runs its own broker rather than share {@link ArtemisBrokerSupport}'s: user {@code viewer} may
 * manage, but not read the acceptors, the disk usage or the divert names; user {@code nomanage} may connect and not
 * manage at all. {@code management-message-rbac} is read only at startup — security settings reload live, it does not —
 * so the configuration is written between the image creating its broker instance and running it, by an entrypoint that
 * does what the image's own does with the edits in between. The setup still waits until a refusal is actually seen.
 */
class PartialAvailabilityIT
{

    private static final String DENIED_OPERATION = "getAcceptorsAsJSON";

    private static GenericContainer<?> container;

    private BrokerSession session;

    @BeforeAll
    static void start() throws Exception
    {
        container = new GenericContainer<>(DockerImageName.parse(ArtemisBrokerSupport.IMAGE))
                .withEnv("ARTEMIS_USER", ArtemisBrokerSupport.USER)
                .withEnv("ARTEMIS_PASSWORD", ArtemisBrokerSupport.PASSWORD).withExposedPorts(61616)
                .withCopyToContainer(Transferable.of(entrypoint(), 0755), "/tmp/rbac-entrypoint.sh")
                .withCreateContainerCmdModifier(create -> create.withEntrypoint("/bin/bash", "/tmp/rbac-entrypoint.sh"))
                .waitingFor(Wait.forLogMessage(".*Server is now active.*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(4));
        container.start();
        awaitRefusal();
    }

    /**
     * The image's own entrypoint — create the instance, then run it — with the edits in between. The properties files
     * end without a newline, so each addition starts with one. Every existing role grant is widened to the two test
     * users first, then 'manage' taken back from nomanage, then the per-operation view rules put in front — which use
     * roles="amq" and so are untouched by the widening.
     */
    private static String entrypoint()
    {
        return String.join("\n", "set -e", "cd /var/lib/artemis-instance",
                "/opt/artemis/bin/artemis create --user \"$ARTEMIS_USER\" --password \"$ARTEMIS_PASSWORD\""
                        + " --silent --require-login .",
                "printf '\\nviewer = viewer\\nnomanage = nomanage\\n' >> etc/artemis-users.properties",
                "printf '\\nviewer = viewer\\nnomanage = nomanage\\n' >> etc/artemis-roles.properties",
                "sed -i 's#roles=\"amq\"#roles=\"amq,viewer,nomanage\"#g;"
                        + " s#type=\"manage\" roles=\"amq,viewer,nomanage\"#type=\"manage\" roles=\"amq,viewer\"#;"
                        + " s#<security-settings>#<management-message-rbac>true</management-message-rbac>"
                        + "<security-settings><security-setting match=\"mops.\\#\">"
                        + "<permission type=\"view\" roles=\"amq,viewer\"/><permission type=\"edit\" roles=\"amq\"/>"
                        + "</security-setting>" + deniedToViewer("mops.broker." + DENIED_OPERATION)
                        + deniedToViewer("mops.broker.getDiskStoreUsage") + deniedToViewer("mops.broker.getDivertNames")
                        + deniedToViewer("mops.address.\\#") + "#' etc/broker.xml",
                "exec ./bin/artemis run", "");
    }

    @AfterAll
    static void stop()
    {
        if (container != null)
        {
            container.stop();
        }
    }

    @AfterEach
    void disconnect()
    {
        if (session != null)
        {
            session.close();
        }
    }

    @Test
    @DisplayName("a refused operation is 'denied' with the broker's reason, and the connection carries on")
    void deniesOneOperationAndCarriesOn()
    {
        BrokerInfoService info = new BrokerInfoService(connectAs("viewer"));

        Reading<List<AcceptorInfo>> acceptors = Reading.attempt(info::acceptors);
        Reading<List<BrokerConnection>> connections = Reading.attempt(info::connections);

        assertEquals(Availability.DENIED, acceptors.availability(), acceptors.detail());
        assertTrue(acceptors.detail().contains("mops.broker." + DENIED_OPERATION), acceptors.detail());
        assertTrue(connections.available(), connections.explained());
        assertFalse(connections.value().isEmpty());
        assertTrue(session.isConnected());
    }

    @Test
    @DisplayName("a refused health attribute is missing with its reason; the rest of health still arrives")
    void isolatesARefusedHealthAttribute()
    {
        BrokerHealth health = new BrokerInfoService(connectAs("viewer")).health();

        // An attribute denied by RBAC reads exactly like one that does not exist — the broker says
        // "Problem while retrieving attribute" for both — so the honest word is "unavailable".
        assertEquals(Availability.UNAVAILABLE, health.diskUsedPercent().availability());
        assertFalse(health.diskPressure());
        assertTrue(health.unchecked().stream().anyMatch(line -> line.startsWith("Disk use")),
                health.unchecked().toString());
        assertTrue(health.maxDiskPercent().available(), health.maxDiskPercent().explained());
        assertTrue(health.connectionCount().available(), health.connectionCount().explained());
        assertTrue(health.running(), health.state().explained());
    }

    @Test
    @DisplayName("diagnose names what it could not check, and still reports what it could")
    void diagnosesAroundARefusal()
    {
        BrokerSession viewer = connectAs("viewer");
        QueueDirectory queues = new QueueDirectory(viewer);
        BrokerInfoService info = new BrokerInfoService(viewer);
        Diagnosis diagnosis = new StuckDiagnosisService(queues, new AddressDirectory(viewer, queues), info,
                new QueueBrowseService(viewer, 200, 200000, 20000, 20_000_000L), new DivertDirectory(viewer),
                new InFlightService(viewer, info, 5000), new RateService(viewer, new RateTracker(), queues),
                new ConnectivityService(viewer)).run(false);

        assertTrue(diagnosis.unchecked().stream().anyMatch(line -> line.startsWith("Disk use")),
                diagnosis.unchecked().toString());
        assertTrue(diagnosis.unchecked().stream().anyMatch(line -> line.startsWith("Exclusive diverts")),
                diagnosis.unchecked().toString());
        // Address attributes denied: whether any address is blocked could not be looked at, and the
        // scan stopped at the first refusal rather than asking every address.
        assertEquals(1, diagnosis.unchecked().stream().filter(line -> line.contains("blocked by an operator")).count(),
                diagnosis.unchecked().toString());
    }

    @Test
    @DisplayName("a snapshot taken by a restricted user lists what it could not collect, and collects the rest")
    void snapshotsAroundARefusal()
    {
        BrokerSession viewer = connectAs("viewer");
        QueueDirectory queues = new QueueDirectory(viewer);
        BrokerInfoService info = new BrokerInfoService(viewer);
        AddressDirectory addresses = new AddressDirectory(viewer, queues);
        RateService rates = new RateService(viewer, new RateTracker(), queues);
        IncidentSnapshot snapshot = new SnapshotService(viewer, info, queues, addresses, rates,
                new StuckDiagnosisService(queues, addresses, info,
                        new QueueBrowseService(viewer, 200, 200000, 20000, 20_000_000L), new DivertDirectory(viewer),
                        new InFlightService(viewer, info, 5000), rates, new ConnectivityService(viewer)),
                new ConnectivityService(viewer), 1000, 200).collect();

        assertEquals(Availability.DENIED, snapshot.acceptors().rows().availability());
        assertEquals(Availability.UNAVAILABLE, snapshot.health().diskUsedPercent().availability());
        assertTrue(snapshot.queues().available());
        String json = SnapshotWriter.toJson(snapshot).get("unavailable").toString();
        assertTrue(json.contains("\"section\":\"acceptors\""), json);
        assertTrue(json.contains("\"item\":\"diskStoreUsedPercent\""), json);
    }

    @Test
    @DisplayName("a user without 'manage' is refused, not told the connection was lost")
    void refusesAUserWithoutManage()
    {
        BrokerSession noManage = connectAs("nomanage");

        ManagementRefusal refused = assertThrows(ManagementRefusal.class,
                () -> new QueueDirectory(noManage).overview());

        assertEquals(Availability.DENIED, refused.availability());
        assertTrue(refused.getMessage().contains("'manage' permission"), refused.getMessage());
        // Before Phase 13 this was a ConnectionLostException, which closes the session and sends the
        // user back to the connect form as if the broker had gone away.
        assertTrue(noManage.isConnected());
        assertEquals(Availability.DENIED,
                Reading.attempt(() -> new BrokerInfoService(noManage).acceptors()).availability());
    }

    private BrokerSession connectAs(String user)
    {
        session = new BrokerSession(10000, 10000);
        session.connect(new BrokerCredentials(container.getHost(), container.getMappedPort(61616), user, user));
        return session;
    }

    private static String deniedToViewer(String match)
    {
        return "<security-setting match=\"" + match + "\"><permission type=\"view\" roles=\"amq\"/></security-setting>";
    }

    /** Until the viewer is actually refused — seen, not assumed. */
    private static void awaitRefusal() throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 60_000;
        String last = "nothing yet";
        while (System.currentTimeMillis() < deadline)
        {
            BrokerSession probe = new BrokerSession(5000, 5000);
            try
            {
                probe.connect(
                        new BrokerCredentials(container.getHost(), container.getMappedPort(61616), "viewer", "viewer"));
                Reading<List<AcceptorInfo>> acceptors = Reading.attempt(new BrokerInfoService(probe)::acceptors);
                if (acceptors.availability() == Availability.DENIED)
                {
                    return;
                }
                last = acceptors.explained();
            }
            catch (BrokerException e)
            {
                last = e.getMessage();
            }
            finally
            {
                probe.close();
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("The test broker never started refusing " + DENIED_OPERATION + ": " + last);
    }
}
