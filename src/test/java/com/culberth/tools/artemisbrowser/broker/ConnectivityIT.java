package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Connectivity read from a real broker that has some: a replication primary ({@code p5-a}) with its backup, a cluster
 * peer ({@code p5-b}), a bridge to the peer and one to a host that does not exist, and an AMQP mirror to the peer plus
 * a sender to nowhere. The same fixture the reads were first recorded against, on each supported version.
 *
 * <p>
 * The app connects to the primary only. The peer and the backup exist so the primary has something true to report about
 * them; nothing here asks them anything. Three brokers per version is slow to start, so one fixture serves every test,
 * and the one that stops the backup runs last.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConnectivityIT
{

    private static final String CLUSTER_USER = "clusteruser";
    private static final String CLUSTER_PASSWORD = "clustersecret";
    private static final String CONNECTOR_SECRET = "connectorsecret";
    private static final String BRIDGE_SECRET = "bridgesecret";

    /**
     * The primary: two bridges, a cluster connection, replication, a mirror and a sender. Credentials that must never
     * leave the broker are placed where the broker keeps them: in a connector URI and in a bridge.
     */
    private static final String PRIMARY = """
        <connectors>
          <connector name="self">tcp://p5-a:61616</connector>
          <connector name="toB">tcp://p5-b:61616</connector>
          <connector name="toBackup">tcp://p5-a-backup:61616</connector>
          <connector name="toNowhere">tcp://p5-nowhere:61616?user=leaky&amp;password=%CONNECTOR_SECRET%</connector>
        </connectors>
        <bridges>
          <bridge name="to-b">
            <queue-name>bridge.src</queue-name>
            <forwarding-address>bridge.dst</forwarding-address>
            <user>artemis</user><password>artemis</password>
            <static-connectors><connector-ref>toB</connector-ref></static-connectors>
          </bridge>
          <bridge name="to-nowhere">
            <queue-name>bridge.lost</queue-name>
            <retry-interval>5000</retry-interval>
            <user>bridgeuser</user><password>%BRIDGE_SECRET%</password>
            <static-connectors><connector-ref>toNowhere</connector-ref></static-connectors>
          </bridge>
        </bridges>
        <ha-policy><replication><primary><group-name>pair-a</group-name></primary></replication></ha-policy>
        %CLUSTER%
        <broker-connections>
          <amqp-connection uri="tcp://p5-b:61616" name="mirror-b" user="artemis" password="artemis">
            <mirror/>
          </amqp-connection>
          <amqp-connection uri="tcp://p5-nowhere:61616" name="sender-nowhere" retry-interval="5000">
            <sender address-match="fed.#"/>
          </amqp-connection>
        </broker-connections>
        """;

    private static Network network;
    private static GenericContainer<?> peer;
    private static GenericContainer<?> primary;
    private static GenericContainer<?> backup;

    private static BrokerSession brokerSession;
    private static QueueDirectory queues;
    private static ConnectivityService connectivity;
    private static StuckDiagnosisService diagnosis;
    private static SnapshotService snapshots;

    @BeforeAll
    static void start() throws Exception
    {
        network = Network.newNetwork();
        peer = broker("p5-b", """
            <connectors>
              <connector name="self">tcp://p5-b:61616</connector>
              <connector name="toA">tcp://p5-a:61616</connector>
            </connectors>
            """ + cluster("toA"),
                "<address name=\"bridge.dst\"><anycast><queue name=\"bridge.dst\"/></anycast></address>",
                ".*Server is now active.*\\n");
        peer.start();
        primary = broker("p5-a",
                PRIMARY.replace("%CONNECTOR_SECRET%", CONNECTOR_SECRET).replace("%BRIDGE_SECRET%", BRIDGE_SECRET)
                        .replace("%CLUSTER%", cluster("toB</connector-ref><connector-ref>toBackup")),
                "<address name=\"bridge.src\"><anycast><queue name=\"bridge.src\"/></anycast></address>"
                        + "<address name=\"bridge.lost\"><anycast><queue name=\"bridge.lost\"/></anycast></address>",
                ".*Server is now active.*\\n");
        primary.start();
        backup = broker("p5-a-backup",
                """
                    <connectors>
                      <connector name="self">tcp://p5-a-backup:61616</connector>
                      <connector name="toA">tcp://p5-a:61616</connector>
                    </connectors>
                    <ha-policy><replication><backup><group-name>pair-a</group-name><allow-failback>true</allow-failback></backup></replication></ha-policy>
                    """ + cluster(
                        "toA"),
                "", ".*AMQ221024.*\\n");
        backup.start();

        brokerSession = new BrokerSession(10000, 10000);
        brokerSession.connect(new BrokerCredentials(primary.getHost(), primary.getMappedPort(61616),
                ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD));
        queues = new QueueDirectory(brokerSession);
        connectivity = new ConnectivityService(brokerSession);
        BrokerInfoService info = new BrokerInfoService(brokerSession);
        AddressDirectory addresses = new AddressDirectory(brokerSession, queues);
        RateService rates = new RateService(brokerSession, new RateTracker(), queues);
        diagnosis = new StuckDiagnosisService(queues, addresses, info,
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L),
                new DivertDirectory(brokerSession), new InFlightService(brokerSession, info, 5000), rates,
                connectivity);
        snapshots = new SnapshotService(brokerSession, info, queues, addresses, rates, diagnosis, connectivity, 1000,
                200);

        seed();
        await("the replica to synchronize, the peer to join, the bridge and mirror to connect", () ->
        {
            Connectivity now = read();
            return now.ha().replicaSync().orElse(false) && !now.clusters().value().get(0).peers().isEmpty()
                    && bridge(now, "to-b").connected() && link(now, "mirror-b").connected()
                    && now.queue("bridge.src").messageCount() == 0;
        });
    }

    /** A cluster connection named c1 over the given static connector refs, with a cluster user no one logs in as. */
    private static String cluster(String connectorRefs)
    {
        return "<cluster-user>" + CLUSTER_USER + "</cluster-user><cluster-password>" + CLUSTER_PASSWORD
                + "</cluster-password><cluster-connections><cluster-connection name=\"c1\">"
                + "<connector-ref>self</connector-ref><message-load-balancing>ON_DEMAND</message-load-balancing>"
                + "<max-hops>1</max-hops><static-connectors><connector-ref>" + connectorRefs
                + "</connector-ref></static-connectors></cluster-connection></cluster-connections>";
    }

    /**
     * The image's own start — create, then run — with this broker's configuration spliced into {@code broker.xml}. Core
     * configuration is {@code xsd:all}, so a second {@code <addresses>} fails validation: addresses go inside the
     * existing one, everything else before {@code </core>}.
     */
    private static GenericContainer<?> broker(String alias, String core, String addresses, String readyLog)
    {
        String entry = String.join("\n", "set -e", "cd /var/lib/artemis-instance",
                "/opt/artemis/bin/artemis create --user \"$ARTEMIS_USER\" --password \"$ARTEMIS_PASSWORD\""
                        + " --silent --require-login .",
                "awk '/<\\/core>/{system(\"cat /tmp/core.xml\")}1' etc/broker.xml > /tmp/b.xml && cp /tmp/b.xml etc/broker.xml",
                "awk '/<\\/addresses>/{system(\"cat /tmp/addresses.xml\")}1' etc/broker.xml > /tmp/b.xml"
                        + " && cp /tmp/b.xml etc/broker.xml",
                "exec ./bin/artemis run", "");
        return new GenericContainer<>(DockerImageName.parse(ArtemisBrokerSupport.IMAGE)).withNetwork(network)
                .withNetworkAliases(alias).withEnv("ARTEMIS_USER", ArtemisBrokerSupport.USER)
                .withEnv("ARTEMIS_PASSWORD", ArtemisBrokerSupport.PASSWORD).withExposedPorts(61616)
                .withCopyToContainer(Transferable.of(entry, 0755), "/tmp/entry.sh")
                .withCopyToContainer(Transferable.of(core), "/tmp/core.xml")
                .withCopyToContainer(Transferable.of(addresses), "/tmp/addresses.xml")
                .withCreateContainerCmdModifier(create -> create.withEntrypoint("/bin/bash", "/tmp/entry.sh"))
                .waitingFor(Wait.forLogMessage(readyLog, 1)).withStartupTimeout(Duration.ofMinutes(4));
    }

    /** Five for the peer, which the bridge takes; three that the bridge to nowhere cannot. */
    private static void seed() throws Exception
    {
        String url = "tcp://" + primary.getHost() + ":" + primary.getMappedPort(61616)
                + "?useTopologyForLoadBalancing=false";
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            try
            {
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                try (MessageProducer src = session.createProducer(session.createQueue("bridge.src"));
                        MessageProducer lost = session.createProducer(session.createQueue("bridge.lost")))
                {
                    for (int i = 0; i < 5; i++)
                    {
                        src.send(session.createTextMessage("to-b " + i));
                    }
                    for (int i = 0; i < 3; i++)
                    {
                        lost.send(session.createTextMessage("lost " + i));
                    }
                }
            }
            finally
            {
                connection.close();
            }
        }
    }

    @AfterAll
    static void stop()
    {
        if (brokerSession != null)
        {
            brokerSession.close();
        }
        for (GenericContainer<?> container : new GenericContainer<?>[]
        { backup, primary, peer
        })
        {
            if (container != null)
            {
                container.stop();
            }
        }
        if (network != null)
        {
            network.close();
        }
    }

    private static Connectivity read()
    {
        return connectivity.collect(Reading.attempt(queues::overview));
    }

    private static Bridge bridge(Connectivity connectivity, String name)
    {
        return connectivity.bridges().value().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
    }

    private static BrokerLink link(Connectivity connectivity, String name)
    {
        return connectivity.brokerLinks().value().stream().filter(l -> l.name().equals(name)).findFirst().orElseThrow();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline)
        {
            if (condition.getAsBoolean())
            {
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    @Test
    @Order(1)
    @DisplayName("the primary reports its replication role, a synchronized replica, and itself, its backup and its peer")
    void readsHaAndTopology()
    {
        Connectivity now = read();

        assertEquals(HaState.Kind.REPLICATION_PRIMARY, now.ha().kind(), now.ha().policy().explained());
        assertTrue(now.ha().replicaSync().value());
        assertTrue(now.ha().clustered().value());
        List<TopologyMember> members = now.topology().value();
        assertEquals(2, members.size(), members.toString());
        TopologyMember self = members.stream().filter(TopologyMember::self).findFirst().orElseThrow();
        assertEquals("p5-a:61616", self.primary());
        assertEquals("p5-a-backup:61616", self.backup());
        assertTrue(members.stream().anyMatch(m -> "p5-b:61616".equals(m.primary())));
    }

    @Test
    @Order(2)
    @DisplayName("the cluster connection names its connected peer and its store-and-forward queue")
    void readsTheClusterConnection()
    {
        Connectivity now = read();

        ClusterLink cluster = now.clusters().value().get(0);
        assertEquals("c1", cluster.name());
        assertTrue(cluster.started());
        assertEquals(1, cluster.peers().size(), cluster.peers().toString());
        String peerNode = cluster.peers().keySet().iterator().next();
        assertTrue(now.forwardQueues(cluster).stream().anyMatch(q -> Connectivity.peerOf(cluster, q).equals(peerNode)),
                "one store-and-forward queue per peer");
        assertEquals(List.of("toB", "toBackup"), cluster.staticConnectors());
    }

    @Test
    @Order(3)
    @DisplayName("bridges: one connected with its queue drained, one unconnected with its messages still on its queue")
    void readsBridges()
    {
        Connectivity now = read();

        Bridge toB = bridge(now, "to-b");
        assertTrue(toB.started() && toB.connected());
        assertEquals("bridge.dst", toB.forwardingAddress());
        assertEquals(5, toB.acknowledged());
        assertEquals(5, toB.sent(), "the broker's 'pending' counter is cumulative sent");
        assertEquals(0, toB.outstanding());
        assertEquals("toB (p5-b:61616)", now.targets(toB.connectors()));

        Bridge lost = bridge(now, "to-nowhere");
        assertTrue(lost.started());
        assertFalse(lost.connected());
        assertEquals(3, now.queue("bridge.lost").messageCount());
        assertFalse(lost.forwardsElsewhere());
    }

    @Test
    @Order(4)
    @DisplayName("broker connections: the mirror is connected and known by its queue, the sender to nowhere is not")
    void readsBrokerConnections()
    {
        Connectivity now = read();

        BrokerLink mirror = link(now, "mirror-b");
        assertTrue(mirror.started() && mirror.connected());
        assertTrue(now.mirror(mirror), "the mirror's internal queue exists");
        assertEquals("AMQP", mirror.protocol());
        BrokerLink sender = link(now, "sender-nowhere");
        assertFalse(sender.connected());
        assertFalse(now.mirror(sender));
    }

    @Test
    @Order(5)
    @DisplayName("diagnose names the unconnected bridge and its backlog, and nothing that is connected")
    void diagnosesDisconnectedPaths()
    {
        Diagnosis result = diagnosis.run(true);

        Finding lost = result.findings().stream().filter(f -> f.title().equals("Bridge 'to-nowhere' is not connected"))
                .findFirst().orElseThrow(() -> new AssertionError(result.findings().toString()));
        assertTrue(lost.isStuck());
        assertEquals("bridge.lost", lost.queue());
        assertTrue(lost.detail().contains("3 message(s) are waiting"), lost.detail());
        assertTrue(result.findings().stream()
                .anyMatch(f -> f.title().equals("Broker connection 'sender-nowhere' is not connected")));
        assertTrue(
                result.findings().stream()
                        .noneMatch(f -> f.title().contains("'to-b'") || f.title().contains("mirror-b")
                                || f.title().contains("synchronized backup") || f.title().contains("cluster")),
                result.findings().toString());
        assertTrue(
                result.unchecked().stream().noneMatch(line -> line.startsWith("Bridges")
                        || line.startsWith("Broker connections") || line.startsWith("Cluster")),
                result.unchecked().toString());
    }

    @Test
    @Order(6)
    @DisplayName("the snapshot carries connectivity and none of the fixture's secrets")
    void snapshotCarriesConnectivityAndNoSecrets()
    {
        IncidentSnapshot snapshot = snapshots.collect();

        assertTrue(snapshot.connectivity().available());
        StringWriter out = new StringWriter();
        SnapshotWriter.writeJson(out, snapshot);
        String json = out.toString();
        assertTrue(json.contains("\"to-nowhere\""), "bridges are in the snapshot");
        assertTrue(json.contains("\"mirror-b\""), "broker connections are in the snapshot");
        for (String secret : List.of(CONNECTOR_SECRET, BRIDGE_SECRET, CLUSTER_PASSWORD))
        {
            assertFalse(json.contains(secret), secret + " leaked into the snapshot");
        }
    }

    @Test
    @Order(7)
    @DisplayName("reading connectivity, diagnosing and snapshotting move and consume nothing")
    void changesNothing()
    {
        Map<String, List<Long>> before = counters();
        read();
        diagnosis.run(true);
        snapshots.collect();
        assertEquals(before, counters());
    }

    private static Map<String, List<Long>> counters()
    {
        return queues.overview().stream().filter(queue -> queue.name().startsWith("bridge."))
                .collect(Collectors.toMap(QueueOverview::name, queue -> List.of(queue.messageCount(),
                        queue.deliveringCount(), queue.messagesAcked(), queue.messagesAdded())));
    }

    @Test
    @Order(8)
    @DisplayName("with the backup stopped the primary reports its replica out of sync, and diagnose says so")
    void stoppedBackup() throws Exception
    {
        backup.stop();
        await("the primary to notice its backup has gone", () -> read().ha().replicaOutOfSync());

        List<Finding> findings = new ArrayList<>(diagnosis.run(false).findings());
        Finding replica = findings.stream().filter(f -> f.title().equals("No synchronized backup for this primary"))
                .findFirst().orElseThrow(() -> new AssertionError(findings.toString()));
        assertNotNull(replica.detail());
        assertFalse(replica.isStuck());
    }
}
