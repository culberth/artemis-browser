package com.culberth.tools.artemisbrowser.compare;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A saved snapshot's JSON, built field by field in the shape {@code SnapshotWriter} writes, so each comparison test can
 * say exactly what differs — including shapes the writer would never produce, since the reader must survive those too.
 */
public final class SnapshotJson
{

    static final ObjectMapper JSON = new ObjectMapper();

    public final ObjectNode root = JSON.createObjectNode();
    private final Instant started;

    private SnapshotJson(Instant started)
    {
        this.started = started;
        root.put("schemaVersion", 1);
        root.put("kind", "artemis-browser incident snapshot");
        ObjectNode collection = root.putObject("collection");
        collection.put("startedAt", started.toString());
        collection.put("finishedAt", started.plusSeconds(2).toString());
        ObjectNode connection = root.putObject("connection");
        connection.put("host", "localhost");
        connection.put("port", 61616);
        connection.put("username", "artemis");
        root.putObject("limits").put("maxRowsPerListing", 1000);

        ObjectNode sections = root.putObject("sections");
        ObjectNode health = sections.putObject("health");
        health.put("collectedAt", started.toString());
        ObjectNode data = health.putObject("data");
        data.put("version", "2.55.0");
        data.put("nodeId", "node-1");
        data.put("uptimeMillis", 3_600_000L);
        data.put("connectionCount", 3L);
        listSection("queues");
        listSection("addresses");
        sections.putObject("addressSettings").putObject("data");
        listSection("consumers").put("total", 0).put("truncated", false);
        listSection("connections").put("total", 0).put("truncated", false);
        ObjectNode diagnosis = sections.putObject("diagnosis");
        diagnosis.put("collectedAt", started.plusSeconds(1).toString());
        diagnosis.putArray("findings");
        diagnosis.putArray("couldNotCheck");
        sections.putObject("trends").putArray("readings");
        root.putArray("unavailable");
        root.putArray("omitted");
    }

    public static SnapshotJson at(String startedAt)
    {
        return new SnapshotJson(Instant.parse(startedAt));
    }

    private ObjectNode listSection(String name)
    {
        ObjectNode node = sections().putObject(name);
        node.put("collectedAt", started.plusSeconds(1).toString());
        node.putArray("data");
        return node;
    }

    public ObjectNode sections()
    {
        return (ObjectNode) root.get("sections");
    }

    public ObjectNode health()
    {
        return (ObjectNode) sections().get("health").get("data");
    }

    public ArrayNode rows(String section)
    {
        return (ArrayNode) sections().get(section).get("data");
    }

    public SnapshotJson uptime(long millis)
    {
        health().put("uptimeMillis", millis);
        return this;
    }

    public SnapshotJson noUptime()
    {
        health().remove("uptimeMillis");
        return this;
    }

    public SnapshotJson node(String nodeId)
    {
        health().put("nodeId", nodeId);
        return this;
    }

    public SnapshotJson nodeDenied()
    {
        health().set("nodeId", unavailable("DENIED", "not permitted for this user"));
        return this;
    }

    public SnapshotJson host(String host)
    {
        ((ObjectNode) root.get("connection")).put("host", host);
        return this;
    }

    public SnapshotJson queue(String name, long id, long messages, long added, long acked)
    {
        ObjectNode row = rows("queues").addObject();
        row.put("name", name);
        row.put("id", id);
        row.put("address", name);
        row.put("internal", false);
        row.put("messageCount", messages);
        row.put("deliveringCount", 0);
        row.put("scheduledCount", 0);
        row.put("consumerCount", 1);
        row.put("paused", false);
        row.put("messagesAdded", added);
        row.put("messagesAcknowledged", acked);
        row.put("messagesExpired", 0);
        row.put("messagesKilled", 0);
        ObjectNode config = row.putObject("configuration");
        config.put("ringSize", -1L);
        config.put("exclusive", false);
        return this;
    }

    public SnapshotJson queueConfig(String queue, String key, ObjectNode value)
    {
        for (var row : rows("queues"))
        {
            if (row.get("name").asString().equals(queue))
            {
                ((ObjectNode) row.get("configuration")).set(key, value);
            }
        }
        return this;
    }

    public SnapshotJson queueConfig(String queue, String key, long value)
    {
        for (var row : rows("queues"))
        {
            if (row.get("name").asString().equals(queue))
            {
                ((ObjectNode) row.get("configuration")).put(key, value);
            }
        }
        return this;
    }

    public SnapshotJson address(String name, long sizeBytes)
    {
        ObjectNode row = rows("addresses").addObject();
        row.put("name", name);
        row.put("routingTypes", "ANYCAST");
        row.put("internal", false);
        row.put("messageCount", 0);
        row.put("addressSizeBytes", sizeBytes);
        row.put("pages", 0);
        return this;
    }

    public SnapshotJson setting(String address, String key, String value)
    {
        ObjectNode data = (ObjectNode) sections().get("addressSettings").get("data");
        ObjectNode settings = data.has(address) ? (ObjectNode) data.get(address) : data.putObject(address);
        settings.put(key, value);
        return this;
    }

    public SnapshotJson settingsDenied(String address)
    {
        ((ObjectNode) sections().get("addressSettings").get("data")).set(address,
                unavailable("DENIED", "not permitted for this user", "AMQ229032 getAddressSettingsAsJSON"));
        return this;
    }

    public SnapshotJson consumer(String connection, String queue, long delivering)
    {
        ObjectNode row = rows("consumers").addObject();
        row.put("consumerId", "0");
        row.put("queue", queue);
        row.put("connectionId", connection);
        row.put("sessionId", "s-" + connection);
        row.put("deliveringCount", delivering);
        row.put("thisTool", false);
        ((ObjectNode) sections().get("consumers")).put("total", rows("consumers").size());
        return this;
    }

    public SnapshotJson connection(String id, String from)
    {
        ObjectNode row = rows("connections").addObject();
        row.put("connectionId", id);
        row.put("clientAddress", from);
        row.put("sessionCount", 1);
        row.put("thisTool", false);
        ((ObjectNode) sections().get("connections")).put("total", rows("connections").size());
        return this;
    }

    /** The listing was cut short: the broker had {@code total} rows. */
    public SnapshotJson truncated(String section, int total)
    {
        ((ObjectNode) sections().get(section)).put("total", total).put("truncated", true);
        return this;
    }

    public SnapshotJson finding(String severity, String title, String queue)
    {
        ObjectNode row = ((ArrayNode) sections().get("diagnosis").get("findings")).addObject();
        row.put("severity", severity);
        row.put("basis", "observed");
        row.put("title", title);
        row.put("detail", "detail of " + title);
        row.put("queue", queue);
        row.putNull("address");
        return this;
    }

    public SnapshotJson couldNotCheck(String line)
    {
        ((ArrayNode) sections().get("diagnosis").get("couldNotCheck")).add(line);
        return this;
    }

    /** The whole section as the writer records one the broker refused. */
    public SnapshotJson denied(String section)
    {
        ObjectNode node = (ObjectNode) sections().get(section);
        node.removeAll();
        node.put("collectedAt", started.plusSeconds(1).toString());
        node.set("data", unavailable("DENIED", "not permitted for this user", "AMQ229032 " + section));
        ObjectNode line = ((ArrayNode) root.get("unavailable")).addObject();
        line.put("section", section);
        line.put("availability", "DENIED");
        line.put("reason", "not permitted for this user");
        line.put("detail", "AMQ229032 " + section);
        return this;
    }

    public static ObjectNode unavailable(String availability, String reason)
    {
        return unavailable(availability, reason, "");
    }

    public static ObjectNode unavailable(String availability, String reason, String detail)
    {
        ObjectNode node = JSON.createObjectNode();
        node.put("unavailable", reason);
        node.put("availability", availability);
        node.put("detail", detail);
        return node;
    }

    public byte[] bytes()
    {
        return JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }

    public SnapshotFile read()
    {
        return read(new SnapshotReader(64L << 20, 50_000), "snapshot.json");
    }

    public SnapshotFile read(SnapshotReader reader, String label)
    {
        byte[] bytes = bytes();
        return reader.read(label, new ByteArrayInputStream(bytes), bytes.length);
    }
}
