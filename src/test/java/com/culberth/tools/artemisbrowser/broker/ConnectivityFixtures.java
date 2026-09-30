package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Connectivity states for tests outside this package, each shaped like one recorded against 2.55.0 and 2.57.0. */
public final class ConnectivityFixtures
{

    private ConnectivityFixtures()
    {
    }

    /** No HA policy and nothing leaving the broker, every part read — the shape most brokers have. */
    public static Connectivity standalone()
    {
        HaState ha = new HaState(Reading.of("Primary Only"), Reading.of("node"), Reading.of(true), Reading.of(false),
                Reading.of(false), Reading.of(false), Reading.of(false), Reading.of(0L), Instant.now());
        return new Connectivity(ha, Reading.of(List.of()), Reading.of(List.of()), Reading.of(List.of()),
                Reading.of(List.of()), Reading.of(List.of()), Reading.of(Map.of()), 0, 0);
    }

    /**
     * A replication primary whose backup stopped, with a cluster peer gone and messages waiting for it, one connected
     * and one unreachable bridge, a connected mirror with a backlog, and a broker-connection listing refused.
     */
    public static Connectivity troubled()
    {
        HaState ha = new HaState(Reading.of("Replication Primary w/quorum voting"), Reading.of("node-a"),
                Reading.of(true), Reading.of(false), Reading.of(false), Reading.of(false), Reading.of(true),
                Reading.of(3L), Instant.now());
        ClusterLink cluster = new ClusterLink("c1", true, Map.of(), 1, "ON_DEMAND", List.of("toB"), "", 0, 0);
        Bridge connected = new Bridge("to-b", "bridge.src", "bridge.dst", "", List.of("toB"), "", true, true, false, 10,
                10, "");
        Bridge lost = new Bridge("to-nowhere", "bridge.lost", "", "region = 'eu'", List.of("toNowhere"), "", true,
                false, false, 0, 0, "");
        BrokerLink mirror = new BrokerLink("mirror-b", "AMQP", "tcp://b:61616?password=[redacted]", true, true);
        QueueOverview src = queue("bridge.src", 0, false);
        QueueOverview waiting = queue("bridge.lost", 6, false);
        QueueOverview forward = queue("$.artemis.internal.sf.c1.node-b", 3, true);
        QueueOverview mirrorQueue = queue("$ACTIVEMQ_ARTEMIS_MIRROR_mirror-b", 2, true);
        return new Connectivity(ha,
                Reading.of(List.of(new TopologyMember("node-a", "a:61616", "a-backup:61616", true))),
                Reading.of(List.of(cluster)), Reading.of(List.of(connected, lost)), Reading.of(List.of(mirror)),
                Reading.of(List.of(new Connector("toB", "b", "61616"), new Connector("toNowhere", "nowhere", "61616"))),
                Reading.of(Map.of(src.name(), src, waiting.name(), waiting, forward.name(), forward, mirrorQueue.name(),
                        mirrorQueue)),
                0, 0);
    }

    /** Every listing refused or unsupported; the HA attributes unavailable. The page must still stand. */
    public static Connectivity refused()
    {
        Reading<String> text = Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute HAPolicy");
        Reading<Boolean> flag = Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute");
        HaState ha = new HaState(text, text, flag, flag, flag, flag, flag,
                Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute"), Instant.now());
        return new Connectivity(ha,
                Reading.missing(Availability.DENIED,
                        "AMQ229032: no permission VIEW on mops.broker.listNetworkTopology"),
                Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute clusterConnectionNames"),
                Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute bridgeNames"),
                Reading.missing(Availability.UNSUPPORTED, "AMQ229069: no operation listBrokerConnections/0"),
                Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute connectorsAsJSON"),
                Reading.failed("the queue listing was refused"), 0, 0);
    }

    static QueueOverview queue(String name, long messages, boolean internal)
    {
        return new QueueOverview(name, name, "ANYCAST", messages, 0, 0, internal ? 1 : 0, messages, 0, true, false,
                internal, 0, 0, QueueBehavior.NOT_COLLECTED, 1L);
    }
}
