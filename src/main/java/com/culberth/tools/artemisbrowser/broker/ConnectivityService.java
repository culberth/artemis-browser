package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads this broker's HA state, the cluster topology it reports, and its outbound paths: cluster connections, core
 * bridges and broker connections. All of it this broker's view — nothing here opens a connection to another broker.
 *
 * <p>
 * The reads, as recorded on 2.55.0 and 2.57.0 in {@code .claude/memory.md}:
 * <ul>
 * <li>eight broker attributes for the HA state;</li>
 * <li>{@code listNetworkTopology} and {@code listBrokerConnections}, one call each, JSON;</li>
 * <li>{@code connectorsAsJSON}, of which only name, host and port are kept — it returns connector credentials in
 * clear;</li>
 * <li>{@code bridgeNames} and {@code clusterConnectionNames}, then one attribute read per field per item, since neither
 * has a listing. Brokers carry a handful; past {@link #ITEM_LIMIT} each the rest are counted, not read.</li>
 * </ul>
 * Backlogs come from the queue listing the caller already has, not from further reads.
 */
@Service
public class ConnectivityService
{

    /** Most bridges, and most cluster connections, read per page — about ten round trips each. */
    static final int ITEM_LIMIT = 100;

    private final BrokerSession brokerSession;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConnectivityService(BrokerSession brokerSession)
    {
        this.brokerSession = brokerSession;
    }

    /** Everything, each part on its own; {@code queues} is the listing the caller already read, or why it has none. */
    public Connectivity collect(Reading<List<QueueOverview>> queues)
    {
        ManagementChannel management = brokerSession.requireManagement();
        HaState ha = ha(management);
        String self = ha.nodeId().orElse(null);
        Reading<List<TopologyMember>> topology = Reading.attempt(() -> topology(management, self));
        int[] clustersNotRead =
        { 0
        };
        Reading<List<ClusterLink>> clusters = Reading.attempt(() -> each(management, "clusterConnectionNames",
                "Cluster connection", clustersNotRead, name -> cluster(management, name)));
        int[] bridgesNotRead =
        { 0
        };
        Reading<List<Bridge>> bridges = Reading.attempt(
                () -> each(management, "bridgeNames", "Bridge", bridgesNotRead, name -> bridge(management, name)));
        Reading<List<BrokerLink>> links = Reading.attempt(() -> brokerLinks(management));
        Reading<List<Connector>> connectors = Reading.attempt(() -> connectors(management));
        Reading<Map<String, QueueOverview>> byName = queues.map(list -> list.stream()
                .collect(Collectors.toMap(QueueOverview::name, Function.identity(), (a, b) -> a, LinkedHashMap::new)));
        return new Connectivity(ha, topology, clusters, bridges, links, connectors, byName, bridgesNotRead[0],
                clustersNotRead[0]);
    }

    HaState ha(ManagementChannel management)
    {
        Instant started = Instant.now();
        return new HaState(string(attribute(management, "HAPolicy")), string(attribute(management, "nodeID")),
                flag(attribute(management, "active")), flag(attribute(management, "backup")),
                flag(attribute(management, "replicaSync")), flag(attribute(management, "sharedStore")),
                flag(attribute(management, "clustered")),
                BrokerInfoService.whole(attribute(management, "pendingMirrorAcks")), started);
    }

    private Reading<Object> attribute(ManagementChannel management, String name)
    {
        Reading<Object> reading = Reading.attempt(() -> management.attribute(ResourceNames.BROKER, name));
        return reading.available() && reading.value() == null
                ? Reading.failed("the broker returned no value for " + name)
                : reading;
    }

    private static Reading<String> string(Reading<Object> raw)
    {
        return raw.map(String::valueOf);
    }

    private static Reading<Boolean> flag(Reading<Object> raw)
    {
        if (!raw.available())
        {
            return raw.absent();
        }
        Boolean value = bool(raw.value());
        return value == null ? Reading.failed("'" + raw.value() + "' is not true or false") : Reading.of(value);
    }

    /** {@code listNetworkTopology}: this broker's view of the cluster's nodes, itself included. */
    List<TopologyMember> topology(ManagementChannel management, String selfNodeId)
    {
        List<TopologyMember> members = new ArrayList<>();
        for (JsonNode node : array(management.invoke(ResourceNames.BROKER, "listNetworkTopology")))
        {
            String nodeId = text(node, "nodeID");
            String primary = text(node, "primary");
            members.add(new TopologyMember(nodeId, primary.isEmpty() ? text(node, "live") : primary,
                    text(node, "backup"), !nodeId.isEmpty() && nodeId.equals(selfNodeId)));
        }
        return members;
    }

    /** {@code listBrokerConnections}: one call; the URI masked, since the broker returns it as configured. */
    List<BrokerLink> brokerLinks(ManagementChannel management)
    {
        List<BrokerLink> links = new ArrayList<>();
        for (JsonNode node : array(management.invoke(ResourceNames.BROKER, "listBrokerConnections")))
        {
            links.add(new BrokerLink(text(node, "name"), text(node, "protocol"), Redaction.uri(text(node, "uri")),
                    required(text(node, "started"), "started"), required(text(node, "connected"), "connected")));
        }
        return links;
    }

    /** Name, host and port only. The rest of each entry can hold credentials, in clear. */
    List<Connector> connectors(ManagementChannel management)
    {
        List<Connector> connectors = new ArrayList<>();
        for (JsonNode node : array(management.attribute(ResourceNames.BROKER, "connectorsAsJSON")))
        {
            JsonNode params = node.get("params");
            connectors.add(new Connector(text(node, "name"), params == null ? "" : text(params, "host"),
                    params == null ? "" : text(params, "port")));
        }
        return connectors;
    }

    Bridge bridge(ManagementChannel management, String name)
    {
        String resource = ResourceNames.BRIDGE + name;
        Map<String, Long> metrics = metrics(management.attribute(resource, "metrics"));
        return new Bridge(name, orEmpty(management.attribute(resource, "queueName")),
                orEmpty(management.attribute(resource, "forwardingAddress")),
                orEmpty(management.attribute(resource, "filterString")),
                strings(management.attribute(resource, "staticConnectors")),
                orEmpty(management.attribute(resource, "discoveryGroupName")),
                required(management.attribute(resource, "started"), "started"),
                required(management.attribute(resource, "connected"), "connected"),
                Boolean.TRUE.equals(bool(management.attribute(resource, "HA"))),
                metrics.getOrDefault("messagesAcknowledged", 0L),
                metrics.getOrDefault("messagesPendingAcknowledgement", 0L),
                orEmpty(management.attribute(resource, "transformerClassName")));
    }

    ClusterLink cluster(ManagementChannel management, String name)
    {
        String resource = ResourceNames.CORE_CLUSTER_CONNECTION + name;
        Map<String, Long> metrics = metrics(management.attribute(resource, "metrics"));
        Map<String, String> peers = new LinkedHashMap<>();
        if (management.attribute(resource, "nodes") instanceof Map<?, ?> nodes)
        {
            nodes.forEach((id, where) -> peers.put(String.valueOf(id), String.valueOf(where)));
        }
        Object hops = management.attribute(resource, "maxHops");
        return new ClusterLink(name, required(management.attribute(resource, "started"), "started"), peers,
                hops instanceof Number number ? number.longValue() : 0L,
                orEmpty(management.attribute(resource, "messageLoadBalancingType")),
                strings(management.attribute(resource, "staticConnectors")),
                orEmpty(management.attribute(resource, "discoveryGroupName")),
                metrics.getOrDefault("messagesAcknowledged", 0L),
                metrics.getOrDefault("messagesPendingAcknowledgement", 0L));
    }

    @FunctionalInterface
    private interface ItemReader<T>
    {
        T read(String name);
    }

    /**
     * Names from a broker attribute, then each item read in turn, up to {@link #ITEM_LIMIT}. An item that fails is
     * looked for again: gone since the listing is fine to leave out, still listed means the panel would be silently
     * incomplete — so the panel says it could not be read instead, as the divert list does.
     */
    private <T> List<T> each(ManagementChannel management, String namesAttribute, String kind, int[] notRead,
            ItemReader<T> reader)
    {
        List<String> names = strings(management.attribute(ResourceNames.BROKER, namesAttribute));
        List<T> items = new ArrayList<>();
        Map<String, BrokerException> unread = new LinkedHashMap<>();
        for (String name : names)
        {
            if (items.size() + unread.size() >= ITEM_LIMIT)
            {
                notRead[0]++;
                continue;
            }
            try
            {
                items.add(reader.read(name));
            }
            catch (BrokerException e)
            {
                unread.put(name, e);
            }
        }
        if (!unread.isEmpty())
        {
            List<String> still = strings(management.attribute(ResourceNames.BROKER, namesAttribute));
            for (Map.Entry<String, BrokerException> failed : unread.entrySet())
            {
                if (still.contains(failed.getKey()))
                {
                    Availability why = failed.getValue() instanceof ManagementRefusal refusal ? refusal.availability()
                            : Availability.FAILED;
                    throw new ManagementRefusal(why, kind + " '" + failed.getKey() + "' exists and could not be read: "
                            + failed.getValue().getMessage(), failed.getValue());
                }
            }
        }
        return items;
    }

    // ------------------------------------------------------------------ findings

    /**
     * Diagnose's connectivity findings: paths this broker reports as down, with what is waiting behind them, and a
     * replication primary whose replica is not synchronized. Each says it is this broker's view. What could not be read
     * goes to {@code unchecked}, so no finding never means "could not look".
     */
    public static void findings(Connectivity connectivity, List<Finding> findings, List<String> unchecked)
    {
        HaState ha = connectivity.ha();
        if (!ha.policy().available())
        {
            unchecked.add(
                    "High availability — whether this broker has a synchronized replica: " + ha.policy().explained());
        }
        else if (ha.kind() == HaState.Kind.REPLICATION_PRIMARY && !ha.replicaSync().available())
        {
            unchecked.add(
                    "Replica synchronization — this broker is a replication primary: " + ha.replicaSync().explained());
        }
        else if (ha.replicaOutOfSync())
        {
            findings.add(replicaNotSynchronized(connectivity));
        }

        if (connectivity.clusters().available())
        {
            connectivity.clusters().value().forEach(cluster -> cluster(connectivity, cluster, findings));
        }
        else
        {
            unchecked.add("Cluster connections — whether peers are connected and messages are waiting for them: "
                    + connectivity.clusters().explained());
        }

        if (connectivity.bridges().available())
        {
            connectivity.bridges().value().forEach(bridge -> bridge(connectivity, bridge, findings));
        }
        else
        {
            unchecked.add("Bridges — whether each is connected, and what waits on its queue: "
                    + connectivity.bridges().explained());
        }
        if (connectivity.bridgesNotRead() > 0 || connectivity.clustersNotRead() > 0)
        {
            unchecked.add((connectivity.bridgesNotRead() + connectivity.clustersNotRead())
                    + " bridge(s) or cluster connection(s) past the first " + ITEM_LIMIT + " of each were not read.");
        }

        if (connectivity.brokerLinks().available())
        {
            connectivity.brokerLinks().value().forEach(link -> brokerLink(connectivity, link, findings));
        }
        else
        {
            unchecked.add("Broker connections (AMQP mirrors, federation, senders) — whether each is connected: "
                    + connectivity.brokerLinks().explained());
        }
    }

    private static Finding replicaNotSynchronized(Connectivity connectivity)
    {
        HaState ha = connectivity.ha();
        String announced = connectivity.topology().orElse(List.of()).stream().filter(TopologyMember::self)
                .filter(TopologyMember::hasBackup).map(TopologyMember::backup).findFirst().orElse(null);
        return new Finding(Finding.WATCH, "No synchronized backup for this primary",
                "This broker's HA policy is '" + ha.policy().value() + "' and it reports replica synchronization as not"
                        + " complete. "
                        + (announced == null ? "No backup is announced in its topology. "
                                : "Its topology still names a backup at " + announced + ", but that entry was seen to"
                                        + " outlast a stopped backup, so it does not show one is running. ")
                        + "Until a backup finishes synchronizing, there is no replica to fail over to.",
                null, null);
    }

    private static void cluster(Connectivity connectivity, ClusterLink cluster, List<Finding> findings)
    {
        for (QueueOverview forward : connectivity.forwardQueues(cluster))
        {
            String peer = Connectivity.peerOf(cluster, forward);
            if (forward.messageCount() > 0 && !cluster.peers().containsKey(peer))
            {
                findings.add(new Finding(Finding.STUCK,
                        forward.messageCount() + " message(s) waiting for cluster peer " + peer,
                        "Cluster connection '" + cluster.name() + "' holds " + forward.messageCount()
                                + " message(s) in its store-and-forward queue for node " + peer
                                + ", and this broker reports no connection to that node. They move when it connects"
                                + " again. This is this broker's view; the peer itself was not inspected.",
                        forward.name(), null));
            }
        }
        if (!cluster.started())
        {
            findings.add(new Finding(Finding.WATCH, "Cluster connection '" + cluster.name() + "' is stopped",
                    "This broker reports the cluster connection as not started, so it neither forwards to nor"
                            + " receives from its peers.",
                    null, null));
        }
        else if (cluster.peers().isEmpty() && !cluster.staticConnectors().isEmpty())
        {
            findings.add(new Finding(Finding.WATCH, "Cluster connection '" + cluster.name() + "' has no peers",
                    "Started, configured with " + connectivity.targets(cluster.staticConnectors())
                            + ", and this broker reports no node connected. Messages for other nodes wait here, and"
                            + " load is not shared. This is this broker's view; the peers were not inspected.",
                    null, null));
        }
    }

    private static void bridge(Connectivity connectivity, Bridge bridge, List<Finding> findings)
    {
        if (bridge.started() && bridge.connected())
        {
            return;
        }
        QueueOverview source = connectivity.queue(bridge.queueName());
        long waiting = source == null ? 0 : source.messageCount();
        String backlog = source == null ? "Its queue '" + bridge.queueName() + "' was not in the queue listing."
                : waiting + " message(s) are waiting on its queue '" + bridge.queueName() + "'.";
        String target = bridge.discovery() ? "discovery group " + bridge.discoveryGroup()
                : connectivity.targets(bridge.connectors());
        if (!bridge.started())
        {
            findings.add(
                    new Finding(waiting > 0 ? Finding.STUCK : Finding.WATCH,
                            "Bridge '" + bridge.name() + "' is stopped",
                            "This broker reports the bridge to " + target
                                    + " as not started, so nothing leaves its queue. " + backlog,
                            bridge.queueName(), null));
            return;
        }
        findings.add(new Finding(waiting > 0 ? Finding.STUCK : Finding.WATCH,
                "Bridge '" + bridge.name() + "' is not connected",
                "Started, and this broker reports no connection to " + target + ". " + backlog
                        + " This is this broker's view; the target broker was not inspected.",
                bridge.queueName(), null));
    }

    private static void brokerLink(Connectivity connectivity, BrokerLink link, List<Finding> findings)
    {
        if (link.started() && link.connected())
        {
            return;
        }
        QueueOverview mirror = connectivity.queue(link.mirrorQueue());
        long waiting = mirror == null ? 0 : mirror.messageCount();
        String kind = mirror == null ? "Broker connection" : "Mirror";
        String backlog = mirror == null ? ""
                : " " + waiting + " mirror record(s) are waiting on '" + link.mirrorQueue() + "'.";
        String state = link.started() ? "is not connected" : "is stopped";
        findings.add(new Finding(waiting > 0 ? Finding.STUCK : Finding.WATCH, kind + " '" + link.name() + "' " + state,
                "This broker reports its " + link.protocol() + " connection to " + link.uri() + " as "
                        + (link.started() ? "started and not connected." : "not started.") + backlog
                        + " This is this broker's view; the other broker was not inspected.",
                mirror == null ? null : mirror.name(), null));
    }

    // ------------------------------------------------------------------ parsing

    private List<JsonNode> array(Object result)
    {
        if (result == null)
        {
            return List.of();
        }
        try
        {
            JsonNode root = objectMapper.readTree(result.toString());
            if (!root.isArray())
            {
                throw new BrokerException("expected a JSON array, got: " + abbreviate(result.toString()));
            }
            List<JsonNode> nodes = new ArrayList<>();
            root.forEach(nodes::add);
            return nodes;
        }
        catch (BrokerException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new BrokerException("Could not read the broker's response: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text)
    {
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    /** A Boolean, or a string holding one; null for anything else. */
    static Boolean bool(Object value)
    {
        if (value instanceof Boolean flag)
        {
            return flag;
        }
        if (value != null && ("true".equalsIgnoreCase(value.toString().trim())
                || "false".equalsIgnoreCase(value.toString().trim())))
        {
            return Boolean.parseBoolean(value.toString().trim());
        }
        return null;
    }

    private static boolean required(Object value, String attribute)
    {
        Boolean flag = bool(value);
        if (flag == null)
        {
            throw new BrokerException("'" + value + "' for " + attribute + " is not true or false");
        }
        return flag;
    }

    /** {@code Object[]} over the management address; a collection is accepted too. Never null. */
    static List<String> strings(Object result)
    {
        List<String> values = new ArrayList<>();
        if (result instanceof Object[] array)
        {
            for (Object value : array)
            {
                values.add(String.valueOf(value));
            }
        }
        else if (result instanceof Collection<?> collection)
        {
            collection.forEach(value -> values.add(String.valueOf(value)));
        }
        return values;
    }

    /** A bridge's or cluster connection's {@code metrics}: a map of Long counters. Anything else is left out. */
    static Map<String, Long> metrics(Object result)
    {
        Map<String, Long> metrics = new LinkedHashMap<>();
        if (result instanceof Map<?, ?> map)
        {
            map.forEach((key, value) ->
            {
                if (value instanceof Number number)
                {
                    metrics.put(String.valueOf(key), number.longValue());
                }
            });
        }
        return metrics;
    }

    private static String orEmpty(Object value)
    {
        return value == null ? "" : value.toString();
    }
}
