package com.culberth.tools.artemisbrowser.broker;

import java.io.PrintWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
