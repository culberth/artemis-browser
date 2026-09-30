package com.culberth.tools.artemisbrowser.broker;

import java.io.PrintWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes an {@link IncidentSnapshot} as structured JSON and as a readable text summary, both from the same model.
 *
 * <p>
 * The JSON is meant to be compared with another snapshot later, so names carry their units ({@code addressSizeBytes},
 * {@code uptimeMillis}), times are ISO-8601 in UTC, and queues are identified by name and broker id. A value the broker
 * did not give is written as an object saying why — {@code {"unavailable":"not permitted for this user",...}} — never
 * as a zero or a null, and is also listed under {@code unavailable} with where it belongs.
 */
public final class SnapshotWriter
{

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TEXT_QUEUES = 20;

    private SnapshotWriter()
    {
    }

    public static void writeJson(Writer out, IncidentSnapshot snapshot)
    {
        JSON.writerWithDefaultPrettyPrinter().writeValue(out, toJson(snapshot));
    }

    public static ObjectNode toJson(IncidentSnapshot snapshot)
    {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode unavailable = JSON.createArrayNode();
        ArrayNode omitted = JSON.createArrayNode();

        root.put("schemaVersion", IncidentSnapshot.SCHEMA_VERSION);
        root.put("kind", "artemis-browser incident snapshot");
        root.put("notice",
                "A sequence of observations taken one after another, not an atomic snapshot of the broker."
                        + " Each section carries its own time. Message bodies and credentials are not included; setting"
                        + " values under secret-looking keys are masked.");
        ObjectNode collection = root.putObject("collection");
        collection.put("startedAt", snapshot.startedAt().toString());
        collection.put("finishedAt", snapshot.finishedAt().toString());
        collection.put("durationMillis", snapshot.durationMillis());

        ConnectionInfo connection = snapshot.connection();
        ObjectNode broker = root.putObject("connection");
        if (connection != null)
        {
            broker.put("host", connection.host());
            broker.put("port", connection.port());
            broker.put("username", connection.username());
        }

        IncidentSnapshot.Limits limits = snapshot.limits();
        ObjectNode limitsNode = root.putObject("limits");
        limitsNode.put("maxRowsPerListing", limits.maxRowsPerListing());
        limitsNode.put("maxAddressSettings", limits.maxAddressSettings());
        limitsNode.put("trendSpacingMillis", limits.trendSpacingMillis());
        limitsNode.put("trendMaxReadings", limits.trendMaxReadings());
        limitsNode.put("trendMaxQueues", limits.trendMaxQueues());
        limitsNode.put("maxAddressPermissions", limits.maxAddressPermissions());
        limitsNode.put("transactionDetailLimit", limits.transactionDetailLimit());
        limitsNode.put("transactionMessagesPerBranch", TransactionService.MESSAGE_LIMIT);

        ObjectNode sections = root.putObject("sections");
        health(sections.putObject("health"), snapshot.health(), unavailable);
        queues(sections, snapshot.queues(), unavailable);
        addresses(sections, snapshot.addresses(), unavailable);
        settings(sections.putObject("addressSettings"), snapshot, unavailable, omitted);
        listing(sections, "acceptors", snapshot.acceptors(), unavailable, omitted, (node, acceptor) ->
        {
            node.put("name", acceptor.name());
            node.put("protocols", acceptor.protocols());
            node.put("host", acceptor.host());
            node.put("port", acceptor.port());
        });
        listing(sections, "connections", snapshot.connections(), unavailable, omitted, (node, c) ->
        {
            node.put("connectionId", c.connectionId());
            node.put("clientAddress", c.clientAddress());
            node.put("created", c.createdText());
            node.put("sessionCount", c.sessionCount());
            node.put("thisTool", c.self());
        });
        listing(sections, "consumers", snapshot.consumers(), unavailable, omitted, (node, c) ->
        {
            node.put("consumerId", c.consumerId());
            node.put("queue", c.queueName());
            node.put("connectionId", c.connectionId());
            node.put("sessionId", c.sessionId());
            node.put("browseOnly", c.browseOnly());
            node.put("deliveringCount", c.deliveringCount());
            node.put("messagesDelivered", c.messagesDelivered());
            node.put("messagesAcknowledged", c.messagesAcknowledged());
            node.put("status", c.status());
            node.put("thisTool", c.self());
        });
        listing(sections, "producers", snapshot.producers(), unavailable, omitted, (node, p) ->
        {
            node.put("producerId", p.producerId());
            node.put("address", p.address());
            node.put("connectionId", p.connectionId());
            node.put("created", p.createdText());
            node.put("messagesSent", p.messagesSent());
            node.put("bytesSent", p.bytesSent());
            node.put("thisTool", p.self());
        });
        connectivity(sections.putObject("connectivity"), snapshot.connectivity(), unavailable, omitted);
        transactions(sections.putObject("transactions"), snapshot.transactions(), unavailable, omitted);
        permissions(sections.putObject("permissions"), snapshot.permissions(), unavailable, omitted);
        trends(sections.putObject("trends"), snapshot.trends());
        diagnosis(sections.putObject("diagnosis"), snapshot.diagnosis(), unavailable);

        root.set("unavailable", unavailable);
        root.set("omitted", omitted);
        return root;
    }

    private static void health(ObjectNode node, BrokerHealth health, ArrayNode unavailable)
    {
        node.put("collectedAt", health.collectedAt().toString());
        ObjectNode data = node.putObject("data");
        reading(data, "version", health.version(), "health", unavailable);
        reading(data, "uptime", health.uptime(), "health", unavailable);
        reading(data, "uptimeMillis", health.uptimeMillis(), "health", unavailable);
        reading(data, "state", health.state(), "health", unavailable);
        reading(data, "nodeId", health.nodeId(), "health", unavailable);
        reading(data, "connectionCount", health.connectionCount(), "health", unavailable);
        reading(data, "sessionCount", health.sessionCount(), "health", unavailable);
        reading(data, "consumerCount", health.consumerCount(), "health", unavailable);
        reading(data, "addressMemoryUsedBytes", health.memoryUsedBytes(), "health", unavailable);
        reading(data, "addressMemoryUsedPercent", health.memoryUsedPercent(), "health", unavailable);
        reading(data, "globalMaxSizeBytes", health.globalMaxBytes(), "health", unavailable);
        reading(data, "diskStoreUsedPercent", health.diskUsedPercent(), "health", unavailable);
        reading(data, "maxDiskUsagePercent", health.maxDiskPercent(), "health", unavailable);
    }

    private static void queues(ObjectNode sections, Reading<List<QueueOverview>> queues, ArrayNode unavailable)
    {
        ObjectNode node = sections.putObject("queues");
        if (!section(node, queues, "queues", unavailable))
        {
            return;
        }
        node.put("count", queues.value().size());
        ArrayNode data = node.putArray("data");
        for (QueueOverview queue : queues.value())
        {
            ObjectNode row = data.addObject();
            row.put("name", queue.name());
            row.put("id", queue.id());
            row.put("address", queue.address());
            row.put("routingType", queue.routingType());
            row.put("durable", queue.durable());
            row.put("paused", queue.paused());
            row.put("internal", queue.internalQueue());
            row.put("messageCount", queue.messageCount());
            row.put("deliveringCount", queue.deliveringCount());
            row.put("scheduledCount", queue.scheduledCount());
            row.put("consumerCount", queue.consumerCount());
            row.put("messagesAdded", queue.messagesAdded());
            row.put("messagesAcknowledged", queue.messagesAcked());
            row.put("messagesExpired", queue.messagesExpired());
            row.put("messagesKilled", queue.messagesKilled());
            QueueBehavior behavior = queue.behavior();
            ObjectNode config = row.putObject("configuration");
            String where = "queues." + queue.name();
            reading(config, "lastValueKey", behavior.lastValueKey(), where, null);
            reading(config, "ringSize", behavior.ringSize(), where, null);
            reading(config, "exclusive", behavior.exclusive(), where, null);
            reading(config, "maxConsumers", behavior.maxConsumers(), where, null);
            reading(config, "purgeOnNoConsumers", behavior.purgeOnNoConsumers(), where, null);
            reading(config, "consumersBeforeDispatch", behavior.consumersBeforeDispatch(), where, null);
            reading(config, "delayBeforeDispatchMillis", behavior.delayBeforeDispatch(), where, null);
            reading(config, "groupRebalance", behavior.groupRebalance(), where, null);
            reading(config, "groupBuckets", behavior.groupBuckets(), where, null);
            reading(config, "groupFirstKey", behavior.groupFirstKey(), where, null);
            reading(config, "enabled", behavior.enabled(), where, null);
            reading(config, "nonDestructive", behavior.nonDestructive(), where, null);
        }
    }

    private static void addresses(ObjectNode sections, Reading<List<AddressOverview>> addresses, ArrayNode unavailable)
    {
        ObjectNode node = sections.putObject("addresses");
        if (!section(node, addresses, "addresses", unavailable))
        {
            return;
        }
        node.put("count", addresses.value().size());
        ArrayNode data = node.putArray("data");
        for (AddressOverview address : addresses.value())
        {
            ObjectNode row = data.addObject();
            String where = "addresses." + address.name();
            row.put("name", address.name());
            row.put("routingTypes", address.routingTypes());
            row.put("internal", address.internal());
            row.put("temporary", address.temporary());
            row.put("queueCount", address.queues().size());
            row.put("messageCount", address.messageCount());
            row.put("routedMessageCount", address.routedMessageCount());
            row.put("unroutedMessageCount", address.unroutedMessageCount());
            row.put("overLimitFlag", address.paging());
            reading(row, "addressSizeBytes", address.addressSize(), where, unavailable);
            reading(row, "addressLimitPercent", address.limitPercent(), where, unavailable);
            reading(row, "pages", address.pages(), where, unavailable);
        }
    }

    private static void settings(ObjectNode node, IncidentSnapshot snapshot, ArrayNode unavailable, ArrayNode omitted)
    {
        node.put("note", "Addresses with something to explain first — near or over a limit, paging, dropping, or"
                + " holding a queue that killed or expired messages — then the rest by name, up to the limit.");
        ObjectNode data = node.putObject("data");
        int masked = 0;
        for (Map.Entry<String, Reading<AddressSettings>> entry : snapshot.addressSettings().entrySet())
        {
            Reading<AddressSettings> read = entry.getValue();
            if (!read.available())
            {
                data.set(entry.getKey(), missing(read));
                unavailable(unavailable, "addressSettings", entry.getKey(), read);
                continue;
            }
            Map<String, String> values = read.value().all();
            masked += Redaction.count(values);
            ObjectNode settings = data.putObject(entry.getKey());
            Redaction.redact(values).forEach(settings::put);
        }
        node.put("maskedValues", masked);
        if (snapshot.addressSettingsOmitted() > 0)
        {
            omitted(omitted, "addressSettings", snapshot.addressSettingsOmitted(),
                    "address settings not read, past artemis.snapshot.max-address-settings");
        }
    }

    private interface Row<T>
    {
        void fill(ObjectNode node, T row);
    }

    private static <T> void listing(ObjectNode sections, String name, IncidentSnapshot.Listing<T> listing,
            ArrayNode unavailable, ArrayNode omitted, Row<T> row)
    {
        ObjectNode node = sections.putObject(name);
        if (!section(node, listing.rows(), name, unavailable))
        {
            return;
        }
        node.put("total", listing.total());
        node.put("truncated", listing.truncated());
        ArrayNode data = node.putArray("data");
        listing.rows().value().forEach(item -> row.fill(data.addObject(), item));
        if (listing.truncated())
        {
            omitted(omitted, name, listing.total() - listing.rows().value().size(),
                    "rows past artemis.snapshot.max-rows");
        }
    }

    /**
     * HA state, topology and outbound paths. Every part is this broker's view; nothing connected to the others. URIs
     * are already masked, connectors carry only name, host and port, and a bridge's cumulative "pending" counter is
     * written under its honest name, {@code messagesSentTotal}.
     */
    private static void connectivity(ObjectNode node, Reading<Connectivity> reading, ArrayNode unavailable,
            ArrayNode omitted)
    {
        if (!section(node, reading, "connectivity", unavailable))
        {
            return;
        }
        Connectivity connectivity = reading.value();
        node.put("note", "As this broker reports it. No other broker was connected to: a peer, backup or target is"
                + " described from this side only, and connected does not mean the far side is healthy.");

        HaState ha = connectivity.ha();
        ObjectNode haNode = node.putObject("ha");
        haNode.put("collectedAt", ha.collectedAt().toString());
        ObjectNode haData = haNode.putObject("data");
        String where = "connectivity.ha";
        reading(haData, "policy", ha.policy(), where, unavailable);
        haData.put("role", ha.role());
        reading(haData, "nodeId", ha.nodeId(), where, unavailable);
        reading(haData, "active", ha.active(), where, unavailable);
        reading(haData, "backup", ha.backup(), where, unavailable);
        if (ha.replicaSyncNotApplicable() != null)
        {
            haData.put("replicaSync", ha.replicaSyncNotApplicable());
        }
        else
        {
            reading(haData, "replicaSync", ha.replicaSync(), where, unavailable);
        }
        reading(haData, "sharedStore", ha.sharedStore(), where, unavailable);
        reading(haData, "clustered", ha.clustered(), where, unavailable);
        reading(haData, "pendingMirrorAcks", ha.pendingMirrorAcks(), where, unavailable);

        ObjectNode topology = node.putObject("topology");
        if (section(topology, connectivity.topology(), "connectivity.topology", unavailable))
        {
            ArrayNode data = topology.putArray("data");
            for (TopologyMember member : connectivity.topology().value())
            {
                ObjectNode row = data.addObject();
                row.put("nodeId", member.nodeId());
                row.put("primary", member.primary());
                row.put("backup", member.backup());
                row.put("thisBroker", member.self());
            }
        }

        ObjectNode clusters = node.putObject("clusterConnections");
        if (section(clusters, connectivity.clusters(), "connectivity.clusterConnections", unavailable))
        {
            ArrayNode data = clusters.putArray("data");
            for (ClusterLink cluster : connectivity.clusters().value())
            {
                ObjectNode row = data.addObject();
                row.put("name", cluster.name());
                row.put("started", cluster.started());
                ObjectNode peers = row.putObject("connectedPeers");
                cluster.peers().forEach(peers::put);
                row.put("maxHops", cluster.maxHops());
                row.put("messageLoadBalancing", cluster.loadBalancing());
                ArrayNode connectors = row.putArray("staticConnectors");
                cluster.staticConnectors().forEach(connectors::add);
                row.put("discoveryGroup", cluster.discoveryGroup());
                row.put("messagesAcknowledged", cluster.acknowledged());
                row.put("messagesSentTotal", cluster.sent());
                ArrayNode forward = row.putArray("forwardQueues");
                for (QueueOverview queue : connectivity.forwardQueues(cluster))
                {
                    ObjectNode q = forward.addObject();
                    String peer = Connectivity.peerOf(cluster, queue);
                    q.put("queue", queue.name());
                    q.put("peerNodeId", peer);
                    q.put("peerConnected", cluster.peers().containsKey(peer));
                    q.put("messageCount", queue.messageCount());
                }
            }
            if (connectivity.clustersNotRead() > 0)
            {
                omitted(omitted, "connectivity.clusterConnections", connectivity.clustersNotRead(),
                        "cluster connections past the first " + ConnectivityService.ITEM_LIMIT);
            }
        }

        ObjectNode bridges = node.putObject("bridges");
        if (section(bridges, connectivity.bridges(), "connectivity.bridges", unavailable))
        {
            ArrayNode data = bridges.putArray("data");
            for (Bridge bridge : connectivity.bridges().value())
            {
                ObjectNode row = data.addObject();
                row.put("name", bridge.name());
                row.put("queue", bridge.queueName());
                QueueOverview source = connectivity.queue(bridge.queueName());
                if (source == null)
                {
                    row.putNull("queueMessageCount");
                }
                else
                {
                    row.put("queueMessageCount", source.messageCount());
                }
                row.put("forwardingAddress", bridge.forwardingAddress());
                row.put("filter", bridge.filter());
                row.put("targets", bridge.discovery() ? "discovery group " + bridge.discoveryGroup()
                        : connectivity.targets(bridge.connectors()));
                row.put("started", bridge.started());
                row.put("connected", bridge.connected());
                row.put("ha", bridge.ha());
                row.put("messagesAcknowledged", bridge.acknowledged());
                row.put("messagesSentTotal", bridge.sent());
                row.put("messagesOutstanding", bridge.outstanding());
                row.put("transformerClassName", bridge.transformerClassName());
            }
            if (connectivity.bridgesNotRead() > 0)
            {
                omitted(omitted, "connectivity.bridges", connectivity.bridgesNotRead(),
                        "bridges past the first " + ConnectivityService.ITEM_LIMIT);
            }
        }

        ObjectNode links = node.putObject("brokerConnections");
        if (section(links, connectivity.brokerLinks(), "connectivity.brokerConnections", unavailable))
        {
            ArrayNode data = links.putArray("data");
            for (BrokerLink link : connectivity.brokerLinks().value())
            {
                ObjectNode row = data.addObject();
                row.put("name", link.name());
                row.put("protocol", link.protocol());
                row.put("uri", link.uri());
                row.put("started", link.started());
                row.put("connected", link.connected());
                QueueOverview mirror = connectivity.queue(link.mirrorQueue());
                row.put("mirror", mirror != null);
                if (mirror != null)
                {
                    row.put("mirrorQueue", mirror.name());
                    row.put("mirrorQueueMessageCount", mirror.messageCount());
                }
            }
        }

        ObjectNode connectors = node.putObject("connectors");
        if (section(connectors, connectivity.connectors(), "connectivity.connectors", unavailable))
        {
            ArrayNode data = connectors.putArray("data");
            for (Connector connector : connectivity.connectors().value())
            {
                ObjectNode row = data.addObject();
                row.put("name", connector.name());
                row.put("host", connector.host());
                row.put("port", connector.port());
            }
        }
    }

    /**
     * Prepared branches with their Xids, creation times and what they send and receive — headers only. Message
     * properties are left out like bodies: they are an application's data, not the broker's state.
     */
    private static void transactions(ObjectNode node, Reading<Transactions> reading, ArrayNode unavailable,
            ArrayNode omitted)
    {
        if (!section(node, reading, "transactions", unavailable))
        {
            return;
        }
        Transactions transactions = reading.value();
        node.put("note", "Only XA branches the broker holds prepared are visible, and branches resolved through its"
                + " management. Active branches, JMS transacted sessions and the transaction manager's own state are"
                + " not reported by the broker. Message properties are not included.");
        ObjectNode data = node.putObject("data");
        if (!transactions.clockNote().isEmpty())
        {
            data.put("creationTimes", transactions.clockNote());
        }
        ObjectNode prepared = data.putObject("prepared");
        if (section(prepared, transactions.prepared(), "transactions.prepared", unavailable))
        {
            prepared.put("count", transactions.preparedTotal());
            prepared.put("detailRead", transactions.detailRead());
            ArrayNode rows = prepared.putArray("branches");
            for (PreparedTransaction tx : transactions.prepared().value())
            {
                branch(rows.addObject(), tx, transactions, omitted);
            }
            if (!transactions.detailRead())
            {
                omitted(omitted, "transactions.prepared", transactions.preparedTotal(),
                        "branches whose messages were not read: " + transactions.detailSkipped());
            }
        }
        xids(data, "heuristicallyCommitted", transactions.heuristicCommitted(), unavailable);
        xids(data, "heuristicallyRolledBack", transactions.heuristicRolledBack(), unavailable);
    }

    private static void branch(ObjectNode row, PreparedTransaction tx, Transactions transactions, ArrayNode omitted)
    {
        row.put("xidBase64", tx.xid());
        if (tx.detailRead())
        {
            row.put("formatId", tx.formatId());
            row.put("globalTransactionId", tx.globalId());
            row.put("branchQualifier", tx.branch());
        }
        row.put("createdAsReported", tx.createdText());
        if (tx.created() != null)
        {
            row.put("createdAt", tx.created().toString());
            row.put("ageMillis", tx.ageMillis(transactions.collectedAt()));
        }
        else
        {
            ObjectNode why = row.putObject("createdAt");
            why.put("unavailable", "not worked out");
            why.put("detail", tx.createdNote());
        }
        if (!tx.detailRead())
        {
            return;
        }
        row.put("messageCount", tx.messageTotal());
        row.put("sends", tx.sends());
        row.put("receives", tx.receives());
        ArrayNode messages = row.putArray("messages");
        for (TransactionMessage message : tx.messages())
        {
            ObjectNode m = messages.addObject();
            m.put("operation", message.operationText());
            m.put("address", message.address());
            m.put("messageId", message.messageId());
            m.put("userId", message.userId());
            m.put("type", message.type());
            if (message.timestamp() > 0)
            {
                m.put("sentAt", Instant.ofEpochMilli(message.timestamp()).toString());
            }
            m.put("durable", message.durable());
            m.put("priority", message.priority());
        }
        if (tx.truncated())
        {
            omitted(omitted, "transactions.prepared." + tx.xid(), tx.messageTotal() - tx.messages().size(),
                    "message(s) of this branch past the first " + TransactionService.MESSAGE_LIMIT);
        }
    }

    private static void xids(ObjectNode data, String field, Reading<List<String>> reading, ArrayNode unavailable)
    {
        if (!reading.available())
        {
            data.set(field, missing(reading));
            unavailable(unavailable, "transactions", field, reading);
            return;
        }
        ArrayNode list = data.putArray(field);
        reading.value().forEach(list::add);
    }

    /**
     * Roles per address. A refusal of the roles operation is the same for every address, so it is listed under
     * {@code unavailable} once, not once per address.
     */
    private static void permissions(ObjectNode node, Reading<Permissions> reading, ArrayNode unavailable,
            ArrayNode omitted)
    {
        if (!section(node, reading, "permissions", unavailable))
        {
            return;
        }
        Permissions permissions = reading.value();
        node.put("note", "Roles from the security setting that matches each address, as the broker reports them."
                + " Which users hold a role is not reported, so a role is not proof of any client's access.");
        ObjectNode data = node.putObject("data");
        reading(data, "securityEnabled", permissions.securityEnabled(), "permissions", unavailable);
        ObjectNode byAddress = data.putObject("byAddress");
        Set<String> listed = new HashSet<>();
        permissions.byAddress().forEach((address, roles) ->
        {
            if (!roles.available())
            {
                byAddress.set(address, missing(roles));
                if (listed.add(roles.availability() + roles.detail()))
                {
                    unavailable(unavailable, "permissions", address, roles);
                }
                return;
            }
            ArrayNode grants = byAddress.putArray(address);
            for (RoleGrant grant : roles.value())
            {
                ObjectNode row = grants.addObject();
                row.put("role", grant.role());
                grant.permissions().forEach(row::put);
            }
        });
        if (permissions.notRead() > 0)
        {
            omitted(omitted, "permissions", permissions.notRead(),
                    "address(es) past the limit whose roles were not read");
        }
    }

    private static void trends(ObjectNode node, Trends trends)
    {
        node.put("note", "Readings this session took while pages were open; nothing older than the first. A new epoch"
                + " follows a broker restart. Intervals across a restart, a changed queue id or a counter that went"
                + " back are not measurements.");
        node.put("spacingMillis", trends.spacingMillis());
        node.put("untrackedQueues", trends.untrackedQueues());
        ArrayNode readings = node.putArray("readings");
        for (Trends.Mark mark : trends.readings())
        {
            ObjectNode row = readings.addObject();
            row.put("takenAt", Instant.ofEpochMilli(mark.takenAt()).toString());
            row.put("epoch", mark.epoch());
            row.put("restartedBefore", mark.restarted());
            row.put("uptimeChecked", mark.uptimeKnown());
        }
        ObjectNode queues = node.putObject("queues");
        trends.byQueue().forEach((name, points) ->
        {
            ArrayNode series = queues.putArray(name);
            for (TrendPoint point : points)
            {
                ObjectNode row = series.addObject();
                row.put("takenAt", Instant.ofEpochMilli(point.takenAt()).toString());
                row.put("epoch", point.epoch());
                row.put("queueId", point.queueId());
                row.put("messageCount", point.depth());
                row.put("messagesAdded", point.added());
                row.put("messagesAcknowledged", point.acked());
                row.put("messagesExpired", point.expired());
                row.put("messagesKilled", point.killed());
                row.put("consumerCount", point.consumers());
            }
        });
    }

    private static void diagnosis(ObjectNode node, Reading<Diagnosis> reading, ArrayNode unavailable)
    {
        if (!section(node, reading, "diagnosis", unavailable))
        {
            return;
        }
        Diagnosis diagnosis = reading.value();
        ArrayNode findings = node.putArray("findings");
        for (Finding finding : diagnosis.findings())
        {
            ObjectNode row = findings.addObject();
            row.put("severity", finding.severity());
            row.put("basis", finding.basis());
            row.put("title", finding.title());
            row.put("detail", finding.detail());
            row.put("explanation", finding.explanation());
            row.put("queue", finding.queue());
            row.put("address", finding.address());
            row.put("clientId", finding.clientId());
            row.put("connectionId", finding.connectionId());
        }
        ArrayNode couldNotCheck = node.putArray("couldNotCheck");
        diagnosis.unchecked().forEach(couldNotCheck::add);
        node.put("inFlightQueuesNotRead", diagnosis.inFlightQueuesNotRead());
        node.put("inFlightMessagesNotRead", diagnosis.inFlightNotRead());
        Rates rates = diagnosis.measured().rates();
        node.put("ratesMeasured", rates.measured());
        node.put("rateIntervalMillis", rates.intervalMillis());
        node.put("ratesSampledForThisRun", diagnosis.measured().sampled());
    }

    /** Writes the section's time, or why it is missing; true when there is data to write. */
    private static boolean section(ObjectNode node, Reading<?> reading, String name, ArrayNode unavailable)
    {
        node.put("collectedAt", reading.collectedAt().toString());
        if (reading.available())
        {
            return true;
        }
        node.set("data", missing(reading));
        unavailable(unavailable, name, null, reading);
        return false;
    }

    private static void reading(ObjectNode node, String field, Reading<?> reading, String where, ArrayNode unavailable)
    {
        if (!reading.available())
        {
            node.set(field, missing(reading));
            if (unavailable != null)
            {
                unavailable(unavailable, where, field, reading);
            }
            return;
        }
        Object value = reading.value();
        if (value == null)
        {
            node.putNull(field);
        }
        else if (value instanceof Long number)
        {
            node.put(field, number);
        }
        else if (value instanceof Double number)
        {
            node.put(field, number);
        }
        else if (value instanceof Boolean flag)
        {
            node.put(field, flag);
        }
        else
        {
            node.put(field, value.toString());
        }
    }

    private static ObjectNode missing(Reading<?> reading)
    {
        ObjectNode node = JSON.createObjectNode();
        node.put("unavailable", reading.reason());
        node.put("availability", reading.availability().name());
        node.put("detail", reading.detail());
        return node;
    }

    private static void unavailable(ArrayNode list, String section, String item, Reading<?> reading)
    {
        ObjectNode row = list.addObject();
        row.put("section", section);
        if (item != null)
        {
            row.put("item", item);
        }
        row.put("availability", reading.availability().name());
        row.put("reason", reading.reason());
        row.put("detail", reading.detail());
    }

    private static void omitted(ArrayNode list, String section, int count, String why)
    {
        ObjectNode row = list.addObject();
        row.put("section", section);
        row.put("count", count);
        row.put("why", why);
    }

    // ---------------------------------------------------------------- text

    /**
     * A summary for reading in a ticket: the broker, the findings, what could not be checked, the busiest queues, the
     * addresses under pressure, the trends and what was left out. The JSON holds the rest.
     */
    public static void writeText(PrintWriter out, IncidentSnapshot snapshot)
    {
        ConnectionInfo connection = snapshot.connection();
        BrokerHealth health = snapshot.health();
        line(out, "artemis-browser incident snapshot (schema " + IncidentSnapshot.SCHEMA_VERSION + ")");
        line(out,
                "Broker " + (connection == null ? "(unknown)" : connection.describe()) + " - version "
                        + text(health.version()) + ", node " + text(health.nodeId()) + ", state " + text(health.state())
                        + ", up " + text(health.uptime()));
        line(out, "Collected " + snapshot.startedAt() + " to " + snapshot.finishedAt() + " ("
                + snapshot.durationMillis() + "ms). A sequence of observations, not an atomic snapshot.");
        line(out, "Message bodies and credentials are not included; secret-looking setting values are masked.");
        line(out, "");

        line(out, "HEALTH");
        line(out,
                "  Disk store used: "
                        + (health.diskUsedText() != null ? health.diskUsedText() : text(health.diskUsedPercent()))
                        + " (producers block at " + text(health.maxDiskPercent()) + "%)");
        line(out, "  Address memory: " + text(health.memoryUsedBytes()) + " bytes, " + text(health.memoryUsedPercent())
                + "% of global-max-size " + text(health.globalMaxBytes()) + " bytes");
        line(out, "  Connections " + text(health.connectionCount()) + ", sessions " + text(health.sessionCount())
                + ", consumers " + text(health.consumerCount()));
        line(out, "");

        Reading<Diagnosis> diagnosis = snapshot.diagnosis();
        if (diagnosis.available())
        {
            List<Finding> findings = diagnosis.value().findings();
            line(out, "FINDINGS (" + findings.size() + ")");
            if (findings.isEmpty())
            {
                line(out, "  None in what diagnose could check.");
            }
            for (Finding finding : findings)
            {
                line(out, "  [" + (finding.isStuck() ? "not moving" : "worth a look")
                        + (finding.isInferred() ? ", inferred" : "") + "] " + finding.title());
                line(out, "      " + finding.detail());
                if (finding.hasExplanation())
                {
                    line(out, "      May be intended: " + finding.explanation());
                }
            }
            if (!diagnosis.value().unchecked().isEmpty())
            {
                line(out, "  Could not check:");
                diagnosis.value().unchecked().forEach(line -> line(out, "    - " + line));
            }
        }
        else
        {
            line(out, "FINDINGS: not made - " + diagnosis.explained());
        }
        line(out, "");

        Reading<List<QueueOverview>> queues = snapshot.queues();
        if (queues.available())
        {
            line(out, "QUEUES (" + queues.value().size() + "; the " + TEXT_QUEUES
                    + " holding most, internal ones left out)");
            line(out, String.format("  %-40s %10s %10s %9s %12s %12s %8s %8s", "queue", "messages", "delivering",
                    "consumers", "added", "acked", "expired", "killed"));
            queues.value().stream().filter(queue -> !queue.internalQueue())
                    .sorted(Comparator.comparingLong(QueueOverview::messageCount).reversed()).limit(TEXT_QUEUES)
                    .forEach(queue -> line(out,
                            String.format("  %-40s %10d %10d %9d %12d %12d %8d %8d%s", queue.name(),
                                    queue.messageCount(), queue.deliveringCount(), queue.consumerCount(),
                                    queue.messagesAdded(), queue.messagesAcked(), queue.messagesExpired(),
                                    queue.messagesKilled(), queue.behavior().badges().isEmpty() ? ""
                                            : "  [" + String.join(", ", queue.behavior().badges()) + "]")));
        }
        else
        {
            line(out, "QUEUES: not read - " + queues.explained());
        }
        line(out, "");

        Reading<List<AddressOverview>> addresses = snapshot.addresses();
        if (addresses.available())
        {
            List<AddressOverview> pressed = addresses.value().stream()
                    .filter(address -> address.underPressure() || address.pagedToDisk() || address.hasUnrouted())
                    .toList();
            line(out, "ADDRESSES (" + addresses.value().size() + "; " + pressed.size()
                    + " near or over a limit, paging or dropping)");
            for (AddressOverview address : pressed)
            {
                AddressSettings.FullPolicy policy = snapshot.addressSettings()
                        .getOrDefault(address.name(), Reading.notCollected("not read"))
                        .map(AddressSettings::addressFullPolicy).orElse(null);
                line(out,
                        "  " + address.name() + ": "
                                + (address.sizeText() == null ? "size unknown" : address.sizeText())
                                + (address.measuredLimitPercent() == null ? ""
                                        : ", " + address.measuredLimitPercent() + "% of its limit")
                                + (address.pagedToDisk() ? ", " + address.pages().value() + " pages" : "")
                                + (address.hasUnrouted() ? ", " + address.unroutedMessageCount() + " unrouted" : "")
                                + (policy == null ? "" : ", policy " + policy));
            }
        }
        else
        {
            line(out, "ADDRESSES: not read - " + addresses.explained());
        }
        line(out, "");

        Trends trends = snapshot.trends();
        line(out, "TRENDS");
        if (trends.empty())
        {
            line(out, "  No readings in this session.");
        }
        else
        {
            line(out, "  " + trends.readings().size() + " reading(s) since " + trends.sinceText()
                    + " this session; one at most every " + trends.spacingText() + ".");
            trends.byQueue().keySet().stream().map(name -> Map.entry(name, trends.of(name)))
                    .filter(entry -> entry.getValue() != null && entry.getValue().comparable()
                            && entry.getValue().depthChange() != 0)
                    .sorted(Comparator
                            .comparingLong(
                                    (Map.Entry<String, QueueTrend> entry) -> Math.abs(entry.getValue().depthChange()))
                            .reversed())
                    .limit(TEXT_QUEUES)
                    .forEach(entry -> line(out, "  " + entry.getKey() + ": " + entry.getValue().depthChangeText()
                            + " over " + entry.getValue().spanText()
                            + (entry.getValue().consumption() == null ? "" : ". " + entry.getValue().consumption())));
        }
        line(out, "");

        connectivityText(out, snapshot.connectivity());
        transactionsText(out, snapshot.transactions());
        permissionsText(out, snapshot.permissions());

        line(out, "CLIENTS");
        line(out, "  " + listingText("connections", snapshot.connections()));
        line(out, "  " + listingText("consumers", snapshot.consumers()));
        line(out, "  " + listingText("producers", snapshot.producers()));
        line(out, "  " + listingText("acceptors", snapshot.acceptors()));
        line(out, "");

        ObjectNode json = toJson(snapshot);
        line(out, "NOT COLLECTED (" + json.get("unavailable").size() + ")");
        json.get("unavailable")
                .forEach(row -> line(out,
                        "  " + row.get("section").asText() + (row.has("item") ? "." + row.get("item").asText() : "")
                                + ": " + row.get("reason").asText()
                                + (row.get("detail").asText().isBlank() ? "" : " - " + row.get("detail").asText())));
        line(out, "OMITTED (" + json.get("omitted").size() + ")");
        json.get("omitted").forEach(row -> line(out,
                "  " + row.get("section").asText() + ": " + row.get("count").asInt() + " " + row.get("why").asText()));
    }

    private static void connectivityText(PrintWriter out, Reading<Connectivity> reading)
    {
        if (!reading.available())
        {
            line(out, "CONNECTIVITY: not read - " + reading.explained());
            line(out, "");
            return;
        }
        Connectivity connectivity = reading.value();
        HaState ha = connectivity.ha();
        line(out, "CONNECTIVITY (as this broker reports it; no other broker was inspected)");
        line(out, "  HA: " + ha.role() + " (policy " + text(ha.policy()) + "), active " + text(ha.active())
                + ", replica sync "
                + (ha.replicaSyncNotApplicable() != null ? ha.replicaSyncNotApplicable() : text(ha.replicaSync()))
                + ", clustered " + text(ha.clustered()));
        if (connectivity.topology().available())
        {
            line(out, "  Topology: " + connectivity.topology().value().size() + " node(s)");
            connectivity.topology().value()
                    .forEach(member -> line(out,
                            "    " + member.nodeId() + " primary " + member.primary()
                                    + (member.hasBackup() ? ", backup " + member.backup() : "")
                                    + (member.self() ? " (this broker)" : "")));
        }
        else
        {
            line(out, "  Topology: not read - " + connectivity.topology().explained());
        }
        if (connectivity.clusters().available())
        {
            for (ClusterLink cluster : connectivity.clusters().value())
            {
                StringBuilder waiting = new StringBuilder();
                for (QueueOverview queue : connectivity.forwardQueues(cluster))
                {
                    if (queue.messageCount() > 0)
                    {
                        waiting.append(", ").append(queue.messageCount()).append(" waiting for ")
                                .append(Connectivity.peerOf(cluster, queue));
                    }
                }
                line(out, "  Cluster connection " + cluster.name() + ": " + (cluster.started() ? "started" : "STOPPED")
                        + ", " + cluster.peers().size() + " peer(s) connected" + waiting);
            }
        }
        else
        {
            line(out, "  Cluster connections: not read - " + connectivity.clusters().explained());
        }
        if (connectivity.bridges().available())
        {
            for (Bridge bridge : connectivity.bridges().value())
            {
                QueueOverview source = connectivity.queue(bridge.queueName());
                line(out,
                        "  Bridge " + bridge.name() + ": " + bridge.queueName() + " -> "
                                + (bridge.discovery() ? "discovery group " + bridge.discoveryGroup()
                                        : connectivity.targets(bridge.connectors()))
                                + ", "
                                + (bridge.started() ? (bridge.connected() ? "connected" : "NOT CONNECTED") : "STOPPED")
                                + (source == null ? "" : ", " + source.messageCount() + " waiting"));
            }
        }
        else
        {
            line(out, "  Bridges: not read - " + connectivity.bridges().explained());
        }
        if (connectivity.brokerLinks().available())
        {
            for (BrokerLink link : connectivity.brokerLinks().value())
            {
                QueueOverview mirror = connectivity.queue(link.mirrorQueue());
                line(out,
                        "  Broker connection " + link.name() + " (" + link.protocol()
                                + (mirror == null ? "" : ", mirror") + ") to " + link.uri() + ": "
                                + (link.started() ? (link.connected() ? "connected" : "NOT CONNECTED") : "STOPPED")
                                + (mirror == null ? "" : ", " + mirror.messageCount() + " waiting"));
            }
        }
        else
        {
            line(out, "  Broker connections: not read - " + connectivity.brokerLinks().explained());
        }
        line(out, "");
    }

    private static void transactionsText(PrintWriter out, Reading<Transactions> reading)
    {
        if (!reading.available())
        {
            line(out, "TRANSACTIONS: not read - " + reading.explained());
            line(out, "");
            return;
        }
        Transactions transactions = reading.value();
        if (!transactions.prepared().available())
        {
            line(out, "TRANSACTIONS: prepared branches not read - " + transactions.prepared().explained());
        }
        else
        {
            line(out, "TRANSACTIONS (" + transactions.preparedTotal() + " prepared XA branch(es))");
            for (PreparedTransaction tx : transactions.prepared().value())
            {
                String age = tx.ageText(transactions.collectedAt());
                line(out,
                        "  " + tx.title() + ": prepared since " + (tx.createdText().isEmpty() ? "?" : tx.createdText())
                                + (age.isEmpty() ? "" : " (" + age + " ago)")
                                + (tx.detailRead()
                                        ? "; holds " + tx.receives() + " received, " + tx.sends() + " sent"
                                                + (tx.addresses().isEmpty() ? ""
                                                        : " on " + String.join(", ", tx.addresses()))
                                        : "; messages not read"));
            }
            if (!transactions.detailRead())
            {
                line(out, "  " + transactions.detailSkipped());
            }
        }
        line(out, "  Resolved by hand: committed " + text(transactions.heuristicCommitted().map(List::size))
                + ", rolled back " + text(transactions.heuristicRolledBack().map(List::size)));
        line(out, "");
    }

    private static void permissionsText(PrintWriter out, Reading<Permissions> reading)
    {
        if (!reading.available())
        {
            line(out, "PERMISSIONS: not read - " + reading.explained());
            line(out, "");
            return;
        }
        Permissions permissions = reading.value();
        long read = permissions.byAddress().values().stream().filter(Reading::available).count();
        line(out, "PERMISSIONS");
        line(out, "  Security enabled: " + text(permissions.securityEnabled()));
        line(out, "  Roles read for " + read + " of " + (permissions.byAddress().size() + permissions.notRead())
                + " address(es); the JSON has them. Roles are not users: which users hold them is not reported.");
        permissions.byAddress().values().stream().filter(roles -> !roles.available()).findFirst()
                .ifPresent(roles -> line(out, "  Not read: " + roles.explained()));
        line(out, "");
    }

    private static String listingText(String name, IncidentSnapshot.Listing<?> listing)
    {
        if (!listing.rows().available())
        {
            return name + ": not read - " + listing.rows().explained();
        }
        return name + ": " + listing.total()
                + (listing.truncated() ? " (first " + listing.rows().value().size() + " kept)" : "");
    }

    private static String text(Reading<?> reading)
    {
        return reading.available() ? String.valueOf(reading.value()) : "(" + reading.reason() + ")";
    }

    /** One line, ending in LF whatever the platform: a download should read the same everywhere. */
    private static void line(PrintWriter out, String text)
    {
        out.print(text);
        out.print('\n');
    }

}
