package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What an incident snapshot must be to be understood offline: versioned, timed, explicit about everything it could not
 * collect or chose to leave out, and carrying no secret.
 */
class SnapshotWriterTest
{

    private static final Instant START = Instant.parse("2026-09-30T08:00:00Z");

    @Test
    @DisplayName("the JSON carries its schema version, collection times, limits and a notice that it is not atomic")
    void completenessMetadata() throws Exception
    {
        JsonNode json = json(snapshot());

        assertEquals(IncidentSnapshot.SCHEMA_VERSION, json.get("schemaVersion").asInt());
        assertEquals("2026-09-30T08:00:00Z", json.get("collection").get("startedAt").asText());
        assertEquals(4200, json.get("collection").get("durationMillis").asLong());
        assertTrue(json.get("notice").asText().contains("not an atomic snapshot"));
        assertEquals(2, json.get("limits").get("maxRowsPerListing").asInt());
        assertTrue(json.get("sections").get("queues").has("collectedAt"));
    }

    @Test
    @DisplayName("a value the broker would not give is written with its reason, never as zero, and listed")
    void unavailableIsExplicit() throws Exception
    {
        JsonNode json = json(snapshot());

        JsonNode disk = json.get("sections").get("health").get("data").get("diskStoreUsedPercent");
        assertTrue(disk.isObject(), "not a number: " + disk);
        assertEquals("UNAVAILABLE", disk.get("availability").asText());
        assertTrue(contains(json.get("unavailable"), "health", "diskStoreUsedPercent"));

        JsonNode acceptors = json.get("sections").get("acceptors");
        assertEquals("DENIED", acceptors.get("data").get("availability").asText());
        assertTrue(contains(json.get("unavailable"), "acceptors", null));
    }

    @Test
    @DisplayName("truncated listings and unread settings are listed as omitted, with counts")
    void omissionsAreListed() throws Exception
    {
        JsonNode json = json(snapshot());

        JsonNode connections = json.get("sections").get("connections");
        assertEquals(3, connections.get("total").asInt());
        assertTrue(connections.get("truncated").asBoolean());
        assertEquals(2, connections.get("data").size());
        assertTrue(omitted(json, "connections", 1));
        assertTrue(omitted(json, "addressSettings", 7));
    }

    @Test
    @DisplayName("secret-looking settings are masked and counted; the connection carries no password")
    void secretsAreExcluded() throws Exception
    {
        JsonNode json = json(snapshot());

        JsonNode settings = json.get("sections").get("addressSettings");
        assertEquals(Redaction.MASK, settings.get("data").get("orders").get("bridgePassword").asText());
        assertEquals("PAGE", settings.get("data").get("orders").get("addressFullMessagePolicy").asText());
        assertEquals(1, settings.get("maskedValues").asInt());
        assertEquals(List.of("host", "port", "username"), fieldNames(json.get("connection")));
        assertFalse(json.toString().contains("s3cr3t"));
    }

    @Test
    @DisplayName("queues carry units, ids and configuration; findings carry basis and explanation")
    void stableIdentifiersAndFindings() throws Exception
    {
        JsonNode json = json(snapshot());

        JsonNode queue = json.get("sections").get("queues").get("data").get(0);
        assertEquals("orders", queue.get("name").asText());
        assertEquals(931, queue.get("id").asLong());
        assertTrue(queue.has("messagesAcknowledged"));
        assertEquals("UNSUPPORTED", queue.get("configuration").get("nonDestructive").get("availability").asText());

        JsonNode finding = json.get("sections").get("diagnosis").get("findings").get(0);
        assertEquals("inferred", finding.get("basis").asText());
        assertEquals("by design", finding.get("explanation").asText());
    }

    @Test
    @DisplayName("the text summary names the broker, the findings, what was not collected and what was left out")
    void textSummary()
    {
        StringWriter out = new StringWriter();
        SnapshotWriter.writeText(new PrintWriter(out, true), snapshot());
        String text = out.toString();

        assertTrue(text.contains("artemis@localhost:61616") || text.contains("localhost:61616"), text);
        assertTrue(text.contains("[not moving, inferred] 'orders' is full"), text);
        assertTrue(text.contains("May be intended: by design"), text);
        assertTrue(text.contains("health.diskStoreUsedPercent: not available from this broker"), text);
        assertTrue(text.contains("connections: 1 rows past artemis.snapshot.max-rows"), text);
        assertTrue(text.contains("connections: 3 (first 2 kept)"), text);
        assertFalse(text.contains("\r"), "LF whatever the platform");
        assertTrue(text.contains("Disk store used: (not available from this broker)"), text);
        assertFalse(text.contains("s3cr3t"));
    }

    @Test
    @DisplayName("redaction masks by key, whatever the value, and leaves the rest alone")
    void redaction()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("keyStorePassword", "x");
        values.put("authToken", "y");
        values.put("autoCreateQueues", "true");
        values.put("deadLetterAddress", "DLQ");

        Map<String, String> masked = Redaction.redact(values);

        assertEquals(Redaction.MASK, masked.get("keyStorePassword"));
        assertEquals(Redaction.MASK, masked.get("authToken"));
        assertEquals("true", masked.get("autoCreateQueues"), "auto is not auth");
        assertEquals("DLQ", masked.get("deadLetterAddress"));
        assertEquals(2, Redaction.count(values));
    }

    private static IncidentSnapshot snapshot()
    {
        BrokerHealth full = BrokerHealth.of("2.55.0", "1 hour", "STARTED", "node-1", 3, 4, 2, 1024, 0, 10, 90);
        BrokerHealth health = new BrokerHealth(full.version(), full.uptime(), full.state(), full.nodeId(),
                full.connectionCount(), full.sessionCount(), full.consumerCount(), full.memoryUsedBytes(),
                full.memoryUsedPercent(),
                Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute diskStoreUsage"),
                full.maxDiskPercent(), full.collectedAt(), full.globalMaxBytes());
        QueueOverview orders = new QueueOverview("orders", "orders", "ANYCAST", 7, 0, 0, 1, 8, 1, true, false, false)
                .withId(931).withBehavior(QueueBehavior.from(new ObjectMapper()
                        .readTree("{\"ringSize\":\"-1\",\"exclusive\":\"false\",\"lastValueKey\":\"\"}")));
        AddressOverview address = new AddressOverview("orders", "ANYCAST", 7, 21679, 8, 0, true, false, false,
                List.of(orders)).withStorage(108, 0);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("addressFullMessagePolicy", "PAGE");
        settings.put("bridgePassword", "s3cr3t");
        Diagnosis diagnosis = new Diagnosis(List.of(Finding
                .atAddress(Finding.STUCK, "'orders' is full", "at 108%", "orders").inferred().explainedBy("by design")),
                0, 0);
        return new IncidentSnapshot(START, START.plusMillis(4200), new ConnectionInfo("localhost", 61616, "artemis"),
                health, Reading.of(List.of(orders)), Reading.of(List.of(address)),
                Map.of("orders", Reading.of(AddressSettings.of(settings))), 7,
                IncidentSnapshot.Listing.of(Reading.missing(Availability.DENIED, "AMQ229032 getAcceptorsAsJSON"), 2),
                IncidentSnapshot.Listing.of(Reading.of(List.of(new BrokerConnection("c1", "10.0.0.1", "", 1, false),
                        new BrokerConnection("c2", "10.0.0.2", "", 1, false),
                        new BrokerConnection("c3", "10.0.0.3", "", 1, true))), 2),
                IncidentSnapshot.Listing.of(Reading.of(List.of()), 2),
                IncidentSnapshot.Listing.of(Reading.of(List.of()), 2), Trends.none(), Reading.of(diagnosis),
                new IncidentSnapshot.Limits(2, 1, 15_000, 240, 500));
    }

    private static JsonNode json(IncidentSnapshot snapshot) throws Exception
    {
        StringWriter out = new StringWriter();
        SnapshotWriter.writeJson(out, snapshot);
        return new ObjectMapper().readTree(out.toString());
    }

    private static boolean contains(JsonNode list, String section, String item)
    {
        for (JsonNode row : list)
        {
            if (section.equals(row.get("section").asText())
                    && (item == null ? !row.has("item") : row.has("item") && item.equals(row.get("item").asText())))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean omitted(JsonNode json, String section, int count)
    {
        for (JsonNode row : json.get("omitted"))
        {
            if (section.equals(row.get("section").asText()) && row.get("count").asInt() == count)
            {
                return true;
            }
        }
        return false;
    }

    private static List<String> fieldNames(JsonNode node)
    {
        return node.propertyNames().stream().toList();
    }
}
