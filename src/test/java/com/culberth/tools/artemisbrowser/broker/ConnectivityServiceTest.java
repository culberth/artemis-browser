package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Connectivity as this broker reports it, parsed from the replies recorded against 2.55.0 and 2.57.0 (identical): a
 * replication primary with its backup, a cluster peer, a connected and an unreachable bridge, and a mirror plus an
 * unreachable AMQP sender. The replies are copied from {@code .claude/memory.md}, not invented.
 */
class ConnectivityServiceTest
{

    private static final String SELF = "02039621-bc9e-11f1-9569-cac81b1a686b";
    private static final String PEER = "fe92ffd8-bc9d-11f1-a11d-56d78e207b15";

    private static final String TOPOLOGY = "[{\"nodeID\":\"" + PEER
            + "\",\"live\":\"p5-b:61616\",\"primary\":\"p5-b:61616\"},{\"nodeID\":\"" + SELF
            + "\",\"live\":\"p5-a:61616\",\"primary\":\"p5-a:61616\",\"backup\":\"p5-a-backup:61616\"}]";

    private static final String BROKER_CONNECTIONS = "[{\"name\":\"sender-nowhere\",\"protocol\":\"AMQP\","
            + "\"started\":true,\"uri\":\"tcp://p5-nowhere:61616?user=amqp&password=amqpsecret\",\"connected\":false},"
            + "{\"name\":\"mirror-b\",\"protocol\":\"AMQP\",\"started\":true,\"uri\":\"tcp://p5-b:61616\",\"connected\":true}]";

    /** As the broker gives it: the credentials of a connector URI in clear under extraProps. */
    private static final String CONNECTORS = "[{\"name\":\"toB\",\"factoryClassName\":\"org.apache.activemq.artemis.core."
            + "remoting.impl.netty.NettyConnectorFactory\",\"params\":{\"port\":\"61616\",\"host\":\"p5-b\"},"
            + "\"extraProps\":{}},{\"name\":\"toNowhere\",\"factoryClassName\":\"x\",\"params\":{\"port\":\"61616\","
            + "\"host\":\"p5-nowhere\",\"trustStorePassword\":\"tsecret\"},\"extraProps\":{\"password\":"
            + "\"connectorsecret\",\"user\":\"leaky\"}}]";

    private BrokerSession brokerSession;
    private ManagementChannel management;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        given(brokerSession.requireManagement()).willReturn(management);
    }

    private void primaryWithEverything()
    {
        broker("HAPolicy", "Replication Primary w/quorum voting");
        broker("nodeID", SELF);
        broker("active", true);
        broker("backup", false);
        broker("replicaSync", true);
        broker("sharedStore", false);
        broker("clustered", true);
        broker("pendingMirrorAcks", 0L);
        broker("bridgeNames", new Object[]
        { "to-b", "to-nowhere"
        });
        broker("clusterConnectionNames", new Object[]
        { "c1"
        });
        broker("connectorsAsJSON", CONNECTORS);
        given(management.invoke(ResourceNames.BROKER, "listNetworkTopology")).willReturn(TOPOLOGY);
        given(management.invoke(ResourceNames.BROKER, "listBrokerConnections")).willReturn(BROKER_CONNECTIONS);

        bridge("to-b", "bridge.src", "bridge.dst", "toB", true, 10L, 10L);
        bridge("to-nowhere", "bridge.lost", null, "toNowhere", false, 0L, 0L);

        String cluster = ResourceNames.CORE_CLUSTER_CONNECTION + "c1";
        given(management.attribute(cluster, "started")).willReturn(true);
        given(management.attribute(cluster, "nodes")).willReturn(new HashMap<>(Map.of(PEER, "p5-b/172.21.0.2:61616")));
        given(management.attribute(cluster, "maxHops")).willReturn(1L);
        given(management.attribute(cluster, "messageLoadBalancingType")).willReturn("ON_DEMAND");
        given(management.attribute(cluster, "staticConnectors")).willReturn(new Object[]
        { "toB", "toBackup"
        });
        given(management.attribute(cluster, "metrics"))
                .willReturn(new HashMap<>(Map.of("messagesAcknowledged", 0L, "messagesPendingAcknowledgement", 0L)));
    }

    private void broker(String attribute, Object value)
    {
        given(management.attribute(ResourceNames.BROKER, attribute)).willReturn(value);
    }

    private void bridge(String name, String queue, String forwardingAddress, String connector, boolean connected,
            long acked, long sent)
    {
        String resource = ResourceNames.BRIDGE + name;
        given(management.attribute(resource, "queueName")).willReturn(queue);
        given(management.attribute(resource, "forwardingAddress")).willReturn(forwardingAddress);
        given(management.attribute(resource, "staticConnectors")).willReturn(new Object[]
        { connector
        });
        given(management.attribute(resource, "started")).willReturn(true);
        given(management.attribute(resource, "connected")).willReturn(connected);
        given(management.attribute(resource, "HA")).willReturn(false);
        given(management.attribute(resource, "metrics")).willReturn(
                new HashMap<>(Map.of("messagesAcknowledged", acked, "messagesPendingAcknowledgement", sent)));
    }

    private static QueueOverview queue(String name, long messages, boolean internal)
    {
        return ConnectivityFixtures.queue(name, messages, internal);
    }

    private Connectivity collect(QueueOverview... queues)
    {
        return new ConnectivityService(brokerSession).collect(Reading.of(List.of(queues)));
    }

    private static List<Finding> findings(Connectivity connectivity, List<String> unchecked)
    {
        List<Finding> findings = new ArrayList<>();
        ConnectivityService.findings(connectivity, findings, unchecked);
        return findings;
    }

    @Test
    @DisplayName("the HA state reads as a replication primary with a synchronized replica")
    void readsTheHaState()
    {
        primaryWithEverything();

        HaState ha = collect().ha();

        assertEquals(HaState.Kind.REPLICATION_PRIMARY, ha.kind());
        assertEquals("replication primary", ha.role());
        assertTrue(ha.replicaSync().value());
        assertFalse(ha.replicaOutOfSync());
        assertNull(ha.replicaSyncNotApplicable());
    }

    @Test
    @DisplayName("Primary Only is standalone, and replica sync is not applicable rather than failed")
    void standaloneIsNotAFailedSync()
    {
        HaState ha = ConnectivityFixtures.standalone().ha();

        assertEquals(HaState.Kind.STANDALONE, ha.kind());
        assertFalse(ha.replicaOutOfSync());
        assertTrue(ha.replicaSyncNotApplicable().startsWith("not applicable"));
        assertTrue(ConnectivityFixtures.standalone().standalone());
        assertTrue(findings(ConnectivityFixtures.standalone(), new ArrayList<>()).isEmpty());
    }

    @Test
    @DisplayName("the topology marks this broker's own entry, and keeps the announced backup")
    void readsTheTopology()
    {
        primaryWithEverything();

        List<TopologyMember> members = collect().topology().value();

        assertEquals(2, members.size());
        TopologyMember self = members.stream().filter(TopologyMember::self).findFirst().orElseThrow();
        assertEquals(SELF, self.nodeId());
        assertEquals("p5-a:61616", self.primary());
        assertEquals("p5-a-backup:61616", self.backup());
        assertFalse(members.get(0).hasBackup());
    }

    @Test
    @DisplayName("connectors keep name, host and port only; their credentials are never read")
    void connectorsDropCredentials()
    {
        primaryWithEverything();

        Connectivity connectivity = collect();

        assertEquals(List.of(new Connector("toB", "p5-b", "61616"), new Connector("toNowhere", "p5-nowhere", "61616")),
                connectivity.connectors().value());
        assertEquals("toB (p5-b:61616), toBackup", connectivity.targets(List.of("toB", "toBackup")));
        assertFalse(connectivity.toString().contains("connectorsecret"));
        assertFalse(connectivity.toString().contains("tsecret"));
    }

    @Test
    @DisplayName("a broker connection's URI has its password masked")
    void brokerConnectionUriIsMasked()
    {
        primaryWithEverything();

        List<BrokerLink> links = collect().brokerLinks().value();

        assertEquals("tcp://p5-nowhere:61616?user=amqp&password=[redacted]", links.get(0).uri());
        assertFalse(links.get(0).connected());
        assertTrue(links.get(1).connected());
    }

    @Test
    @DisplayName("a bridge's 'pending' counter is cumulative sent, so outstanding is the difference")
    void bridgePendingIsCumulative()
    {
        primaryWithEverything();

        Bridge bridge = collect().bridges().value().get(0);

        assertEquals("to-b", bridge.name());
        assertEquals(10L, bridge.sent());
        assertEquals(10L, bridge.acknowledged());
        assertEquals(0L, bridge.outstanding());
        assertTrue(bridge.forwardsElsewhere());
        assertFalse(collect().bridges().value().get(1).forwardsElsewhere());
    }

    @Test
    @DisplayName("an unconnected bridge with messages on its queue is not moving; the finding names the queue")
    void unconnectedBridgeWithBacklogIsStuck()
    {
        primaryWithEverything();
        List<String> unchecked = new ArrayList<>();

        List<Finding> findings = findings(collect(queue("bridge.src", 0, false), queue("bridge.lost", 6, false)),
                unchecked);

        Finding bridge = findings.stream().filter(f -> f.title().contains("to-nowhere")).findFirst().orElseThrow();
        assertTrue(bridge.isStuck());
        assertEquals("bridge.lost", bridge.queue());
        assertTrue(bridge.detail().contains("6 message(s) are waiting"), bridge.detail());
        assertTrue(bridge.detail().contains("toNowhere (p5-nowhere:61616)"), bridge.detail());
        assertTrue(bridge.detail().contains("the target broker was not inspected"), bridge.detail());
        assertTrue(findings.stream().noneMatch(f -> f.title().contains("'to-b'")), "a connected bridge is fine");
        assertTrue(unchecked.isEmpty(), unchecked.toString());
    }

    @Test
    @DisplayName("an unconnected bridge with nothing waiting is worth a look, not stuck")
    void unconnectedBridgeWithoutBacklogIsWatch()
    {
        primaryWithEverything();

        Finding bridge = findings(collect(queue("bridge.lost", 0, false)), new ArrayList<>()).stream()
                .filter(f -> f.title().contains("to-nowhere")).findFirst().orElseThrow();

        assertFalse(bridge.isStuck());
    }

    @Test
    @DisplayName("an unconnected mirror with records waiting is stuck, and is called a mirror")
    void unconnectedMirror()
    {
        primaryWithEverything();
        given(management.invoke(ResourceNames.BROKER, "listBrokerConnections"))
                .willReturn("[{\"name\":\"mirror-b\",\"protocol\":\"AMQP\",\"started\":true,"
                        + "\"uri\":\"tcp://p5-b:61616\",\"connected\":false}]");

        Connectivity connectivity = collect(queue("$ACTIVEMQ_ARTEMIS_MIRROR_mirror-b", 4, true));
        Finding mirror = findings(connectivity, new ArrayList<>()).stream().filter(f -> f.title().startsWith("Mirror"))
                .findFirst().orElseThrow();

        assertTrue(connectivity.mirror(connectivity.brokerLinks().value().get(0)));
        assertTrue(mirror.isStuck());
        assertEquals("$ACTIVEMQ_ARTEMIS_MIRROR_mirror-b", mirror.queue());
    }

    @Test
    @DisplayName("a replication primary whose replica is not synchronized says the topology's backup is no proof")
    void replicaNotSynchronized()
    {
        primaryWithEverything();
        broker("replicaSync", false);

        Finding finding = findings(collect(), new ArrayList<>()).stream()
                .filter(f -> f.title().contains("synchronized backup")).findFirst().orElseThrow();

        assertFalse(finding.isStuck());
        assertTrue(finding.detail().contains("p5-a-backup:61616"), finding.detail());
        assertTrue(finding.detail().contains("does not show one is running"), finding.detail());
    }

    @Test
    @DisplayName("messages waiting for a cluster peer that is not connected are not moving")
    void messagesForADisconnectedPeer()
    {
        primaryWithEverything();
        given(management.attribute(ResourceNames.CORE_CLUSTER_CONNECTION + "c1", "nodes")).willReturn(new HashMap<>());

        List<Finding> findings = findings(collect(queue("$.artemis.internal.sf.c1." + PEER, 3, true)),
                new ArrayList<>());

        Finding waiting = findings.stream().filter(f -> f.title().contains("waiting for cluster peer")).findFirst()
                .orElseThrow();
        assertTrue(waiting.isStuck());
        assertEquals("$.artemis.internal.sf.c1." + PEER, waiting.queue());
        assertTrue(findings.stream().anyMatch(f -> f.title().contains("has no peers")));
    }

    @Test
    @DisplayName("a connected peer's store-and-forward queue is not a finding")
    void connectedPeerIsFine()
    {
        primaryWithEverything();

        List<Finding> findings = findings(
                collect(queue("$.artemis.internal.sf.c1." + PEER, 3, true), queue("bridge.lost", 0, false)),
                new ArrayList<>());

        assertTrue(findings.stream().noneMatch(f -> f.title().contains("cluster")), findings.toString());
    }

    @Test
    @DisplayName("a refused listing leaves the rest standing and is named as not checked")
    void refusalIsUnchecked()
    {
        primaryWithEverything();
        given(management.invoke(ResourceNames.BROKER, "listBrokerConnections"))
                .willThrow(new ManagementRefusal(Availability.UNSUPPORTED, "no operation listBrokerConnections/0"));
        List<String> unchecked = new ArrayList<>();

        Connectivity connectivity = collect(queue("bridge.lost", 0, false));
        findings(connectivity, unchecked);

        assertEquals(Availability.UNSUPPORTED, connectivity.brokerLinks().availability());
        assertTrue(connectivity.bridges().available());
        assertEquals(1, unchecked.size(), unchecked.toString());
        assertTrue(unchecked.get(0).startsWith("Broker connections"));
    }

    @Test
    @DisplayName("a bridge that exists and cannot be read makes the list say it is incomplete, not shorter")
    void unreadableBridgeStillListed()
    {
        primaryWithEverything();
        given(management.attribute(ResourceNames.BRIDGE + "to-nowhere", "connected"))
                .willThrow(new ManagementRefusal(Availability.UNAVAILABLE, "Problem while retrieving attribute"));

        Reading<List<Bridge>> bridges = collect().bridges();

        assertEquals(Availability.UNAVAILABLE, bridges.availability());
        assertTrue(bridges.detail().contains("to-nowhere"), bridges.detail());
    }

    @Test
    @DisplayName("a bridge gone between the listing and its read is left out quietly")
    void bridgeGoneSinceListing()
    {
        primaryWithEverything();
        given(management.attribute(ResourceNames.BRIDGE + "to-nowhere", "connected"))
                .willThrow(new ManagementRefusal(Availability.UNAVAILABLE, "Problem while retrieving attribute"));
        given(management.attribute(ResourceNames.BROKER, "bridgeNames")).willReturn(new Object[]
        { "to-b", "to-nowhere"
        }, new Object[]
        { "to-b"
        });

        Reading<List<Bridge>> bridges = collect().bridges();

        assertTrue(bridges.available());
        assertEquals(1, bridges.value().size());
    }

    @Test
    @DisplayName("a flag in an unexpected shape is 'could not be read', never false")
    void oddFlagIsNotFalse()
    {
        primaryWithEverything();
        broker("replicaSync", "maybe");

        HaState ha = collect().ha();

        assertEquals(Availability.FAILED, ha.replicaSync().availability());
        assertFalse(ha.replicaOutOfSync());
    }

    @Test
    @DisplayName("an unreadable HA policy is named as not checked")
    void unreadablePolicy()
    {
        primaryWithEverything();
        given(management.attribute(ResourceNames.BROKER, "HAPolicy"))
                .willThrow(new ManagementRefusal(Availability.UNAVAILABLE, "Problem while retrieving attribute"));
        List<String> unchecked = new ArrayList<>();

        findings(collect(), unchecked);

        assertTrue(unchecked.stream().anyMatch(line -> line.startsWith("High availability")), unchecked.toString());
    }

    @Test
    @DisplayName("past the item limit bridges are counted, not read")
    void boundedPerKind()
    {
        primaryWithEverything();
        Object[] names = new Object[ConnectivityService.ITEM_LIMIT + 3];
        for (int i = 0; i < names.length; i++)
        {
            names[i] = "b" + i;
            bridge("b" + i, "q" + i, null, "toB", true, 0L, 0L);
        }
        broker("bridgeNames", names);

        Connectivity connectivity = collect();

        assertEquals(ConnectivityService.ITEM_LIMIT, connectivity.bridges().value().size());
        assertEquals(3, connectivity.bridgesNotRead());
    }

    @Test
    @DisplayName("the shared-store policies are recognised by name")
    void sharedStoreKinds()
    {
        HaState backup = new HaState(Reading.of("Shared Store Backup"), Reading.of("n"), Reading.of(false),
                Reading.of(true), Reading.of(false), Reading.of(true), Reading.of(true), Reading.of(0L), Instant.now());

        assertEquals(HaState.Kind.SHARED_STORE_BACKUP, backup.kind());
        assertTrue(backup.replicaSyncNotApplicable().contains("shared-store"));
    }

    @Test
    @DisplayName("an anything-else attribute read is not needed for the unused ones")
    void unusedAttributesAreNotRead()
    {
        primaryWithEverything();
        given(management.attribute(anyString(), org.mockito.ArgumentMatchers.eq("transformerPropertiesAsJSON")))
                .willThrow(new AssertionError("transformer properties can hold secrets and are never read"));
        given(management.attribute(anyString(), org.mockito.ArgumentMatchers.eq("user")))
                .willThrow(new AssertionError("a broker connection's user is not read"));

        assertTrue(collect().bridges().available());
    }
}
