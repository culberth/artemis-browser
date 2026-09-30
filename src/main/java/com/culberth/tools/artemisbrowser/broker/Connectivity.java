package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where messages can leave this broker, and its high-availability relationships — everything as this broker reports it.
 *
 * <p>
 * Nothing here connects to another broker. A peer, a backup or a bridge target is described from this side only: the
 * broker says it is connected, or not, and how much is waiting to go. Whether the other side is healthy is a question
 * for that broker. Each part is its own reading, so a broker that refuses one still shows the rest.
 *
 * @param queues          the queue listing by name, for backlogs: a bridge's source queue, a cluster peer's
 *                        store-and-forward queue, a mirror's queue
 * @param bridgesNotRead  bridges past {@link ConnectivityService#ITEM_LIMIT}, named but not read
 * @param clustersNotRead cluster connections past the same limit
 */
public record Connectivity(HaState ha, Reading<List<TopologyMember>> topology, Reading<List<ClusterLink>> clusters,
        Reading<List<Bridge>> bridges, Reading<List<BrokerLink>> brokerLinks, Reading<List<Connector>> connectors,
        Reading<Map<String, QueueOverview>> queues, int bridgesNotRead, int clustersNotRead)
{

    /** The listed queue of this name, or null when it is not there or the listing could not be read. */
    public QueueOverview queue(String name)
    {
        return queues.available() && name != null ? queues.value().get(name) : null;
    }

    /** {@code toB (p5-b:61616)} for each connector name, or the bare name when the connectors could not be read. */
    public String targets(List<String> connectorNames)
    {
        List<String> described = new ArrayList<>();
        for (String name : connectorNames)
        {
            Connector connector = connectors.available()
                    ? connectors.value().stream().filter(c -> c.name().equals(name)).findFirst().orElse(null)
                    : null;
            described.add(connector == null ? name : name + " (" + connector.target() + ")");
        }
        return String.join(", ", described);
    }

    /** A broker connection with a mirror queue is a mirror; nothing else in the listing says so. */
    public boolean mirror(BrokerLink link)
    {
        return queue(link.mirrorQueue()) != null;
    }

    /** The store-and-forward queues of one cluster connection, one per peer it has had. */
    public List<QueueOverview> forwardQueues(ClusterLink cluster)
    {
        if (!queues.available())
        {
            return List.of();
        }
        return queues.value().values().stream().filter(q -> q.name().startsWith(cluster.forwardQueuePrefix())).toList();
    }

    /** The peer node id a store-and-forward queue sends to: the part of its name after the prefix. */
    public static String peerOf(ClusterLink cluster, QueueOverview forwardQueue)
    {
        return forwardQueue.name().substring(cluster.forwardQueuePrefix().length());
    }

    /** True when every part was read and there is nothing to show: no HA policy, no cluster, bridge or link. */
    public boolean standalone()
    {
        return ha.kind() == HaState.Kind.STANDALONE && empty(clusters) && empty(bridges) && empty(brokerLinks);
    }

    private static boolean empty(Reading<? extends List<?>> reading)
    {
        return reading.available() && reading.value().isEmpty();
    }
}
