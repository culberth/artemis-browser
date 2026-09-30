package com.culberth.tools.artemisbrowser.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.broker.Availability;
import com.culberth.tools.artemisbrowser.broker.BrokerHealth;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.Diagnosis;
import com.culberth.tools.artemisbrowser.broker.Finding;
import com.culberth.tools.artemisbrowser.broker.IncidentSnapshot;
import com.culberth.tools.artemisbrowser.broker.QueueOverview;
import com.culberth.tools.artemisbrowser.broker.Reading;
import com.culberth.tools.artemisbrowser.broker.SnapshotWriter;
import com.culberth.tools.artemisbrowser.broker.Trends;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** A saved snapshot is untrusted input: read only what is well-formed, bounded and of a known schema. */
class SnapshotReaderTest
{

    private final SnapshotReader reader = new SnapshotReader(64L << 20, 1_000);

    @Test
    @DisplayName("what the snapshot writer writes, the reader reads back — including a value the broker refused")
    void readsTheWritersOutput()
    {
        QueueOverview orders = new QueueOverview("orders", "orders", "ANYCAST", 7, 1, 0, 2, 8, 1, true, false, false)
                .withId(931);
        BrokerHealth full = BrokerHealth.of("2.55.0", "1 hour", "STARTED", "node-1", 3, 4, 2, 1024, 0, 10, 90);
        BrokerHealth health = new BrokerHealth(full.version(), full.uptime(), full.state(), full.nodeId(),
                full.connectionCount(), full.sessionCount(), full.consumerCount(), full.memoryUsedBytes(),
                full.memoryUsedPercent(), Reading.missing(Availability.DENIED, "AMQ229032 diskStoreUsage"),
                full.maxDiskPercent(), full.collectedAt(), full.globalMaxBytes(), Reading.of(90_000L));
        Diagnosis diagnosis = new Diagnosis(
                List.of(Finding.stuck("Nothing is reading 'orders'", "7 waiting", "orders")), 0, 0);
        IncidentSnapshot snapshot = new IncidentSnapshot(Instant.parse("2026-09-30T08:00:00Z"),
                Instant.parse("2026-09-30T08:00:03Z"), new ConnectionInfo("localhost", 61616, "artemis"), health,
                Reading.of(List.of(orders)), Reading.of(List.of()), Map.of(), 0,
                IncidentSnapshot.Listing.of(Reading.of(List.of()), 10),
                IncidentSnapshot.Listing.of(Reading.of(List.of()), 10),
                IncidentSnapshot.Listing.of(Reading.missing(Availability.DENIED, "AMQ229032 listConsumers"), 10),
                IncidentSnapshot.Listing.of(Reading.of(List.of()), 10), Reading.notCollected("not in this test"),
                Reading.notCollected("not in this test"), Reading.notCollected("not in this test"), Trends.none(),
                Reading.of(diagnosis), new IncidentSnapshot.Limits(10, 10, 15_000, 240, 500, 10, 10));
        StringWriter json = new StringWriter();
        SnapshotWriter.writeJson(json, snapshot);
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);

        SnapshotFile file = reader.read("s.json", new ByteArrayInputStream(bytes), bytes.length);

        assertEquals("node-1", file.nodeId().text());
        assertEquals(90_000L, file.uptimeMillis().number());
        assertEquals("localhost:61616", file.address());
        Value disk = file.health().get("diskStoreUsedPercent");
        assertFalse(disk.known());
        assertEquals("not permitted for this user", disk.missing());
        SnapshotFile.QueueRow queue = file.queues().data().get("orders");
        assertEquals(7L, queue.levels().get("messageCount").number());
        assertEquals(8L, queue.counters().get("messagesAdded").number());
        assertEquals("931", queue.id().text());
        assertFalse(file.consumers().rows().available());
        assertEquals("not permitted for this user", file.consumers().rows().why());
        assertEquals(1, file.diagnosis().data().findings().size());
        assertTrue(file.unavailable().stream().anyMatch(line -> line.startsWith("consumers:")),
                file.unavailable().toString());
    }

    @Test
    @DisplayName("the trends section is streamed past, whatever it holds")
    void skipsTrends()
    {
        SnapshotJson json = SnapshotJson.at("2026-09-30T08:00:00Z");
        ArrayNode readings = (ArrayNode) json.sections().get("trends").get("readings");
        for (int i = 0; i < 5_000; i++)
        {
            readings.addObject().put("takenAt", "x");
        }

        // Over the 1,000-row limit, but in a section the comparison never reads.
        SnapshotFile file = json.read(reader, "long-session.json");

        assertEquals(Instant.parse("2026-09-30T08:00:00Z"), file.startedAt());
    }

    @Test
    @DisplayName("a file that is not JSON is refused with its name")
    void notJson()
    {
        SnapshotRejected e = assertThrows(SnapshotRejected.class, () -> read("notes.json", "hello"));
        assertTrue(e.getMessage().startsWith("notes.json is not a JSON file"), e.getMessage());
    }

    @Test
    @DisplayName("the text summary, or any other JSON, is not an incident snapshot")
    void wrongKind()
    {
        SnapshotRejected text = assertThrows(SnapshotRejected.class,
                () -> read("incident.txt", "artemis-browser incident snapshot (schema 1)\nBroker ..."));
        assertTrue(text.getMessage().contains("not a JSON file"), text.getMessage());

        SnapshotRejected other = assertThrows(SnapshotRejected.class, () -> read("x.json", "{\"kind\":\"else\"}"));
        assertTrue(other.getMessage().contains("text summary cannot"), other.getMessage());

        SnapshotRejected array = assertThrows(SnapshotRejected.class, () -> read("x.json", "[1,2]"));
        assertTrue(array.getMessage().contains("not a JSON object"), array.getMessage());
    }

    @Test
    @DisplayName("a schema this build does not know is refused rather than guessed at")
    void unknownSchema()
    {
        SnapshotJson json = SnapshotJson.at("2026-09-30T08:00:00Z");
        json.root.put("schemaVersion", 2);
        SnapshotRejected e = assertThrows(SnapshotRejected.class, () -> json.read(reader, "future.json"));
        assertTrue(e.getMessage().contains("schema 2; this build compares schema 1 only"), e.getMessage());

        json.root.put("schemaVersion", "1");
        SnapshotRejected quoted = assertThrows(SnapshotRejected.class, () -> json.read(reader, "odd.json"));
        assertTrue(quoted.getMessage().contains("no schema version"), quoted.getMessage());
    }

    @Test
    @DisplayName("without a collection time a snapshot cannot be put in order")
    void noCollectionTime()
    {
        SnapshotJson json = SnapshotJson.at("2026-09-30T08:00:00Z");
        ((ObjectNode) json.root.get("collection")).put("startedAt", "yesterday");
        SnapshotRejected e = assertThrows(SnapshotRejected.class, () -> json.read(reader, "x.json"));
        assertTrue(e.getMessage().contains("does not say when it was collected"), e.getMessage());
    }

    @Test
    @DisplayName("size, row count, depth, duplicate keys and trailing content are all bounded")
    void bounded()
    {
        byte[] bytes = SnapshotJson.at("2026-09-30T08:00:00Z").bytes();
        SnapshotReader small = new SnapshotReader(100, 1_000);
        SnapshotRejected declared = assertThrows(SnapshotRejected.class,
                () -> small.read("big.json", new ByteArrayInputStream(bytes), bytes.length));
        assertTrue(declared.getMessage().contains("over the 100-byte limit"), declared.getMessage());
        // A size not declared up front is still caught while reading.
        SnapshotRejected streamed = assertThrows(SnapshotRejected.class,
                () -> small.read("big.json", new ByteArrayInputStream(bytes), -1));
        assertTrue(streamed.getMessage().contains("not a JSON file"), streamed.getMessage());

        SnapshotJson many = SnapshotJson.at("2026-09-30T08:00:00Z");
        for (int i = 0; i < 1_001; i++)
        {
            many.queue("q" + i, i, 0, 0, 0);
        }
        SnapshotRejected rows = assertThrows(SnapshotRejected.class, () -> many.read(reader, "many.json"));
        assertTrue(rows.getMessage().contains("1001 entries in queues, over the 1000-row limit"), rows.getMessage());

        String deep = "{\"a\":".repeat(100) + "1" + "}".repeat(100);
        assertThrows(SnapshotRejected.class, () -> read("deep.json", deep));

        SnapshotRejected duplicate = assertThrows(SnapshotRejected.class,
                () -> read("dup.json", "{\"kind\":\"artemis-browser incident snapshot\",\"kind\":\"x\"}"));
        assertTrue(duplicate.getMessage().contains("not a JSON file"), duplicate.getMessage());

        String trailing = new String(bytes, StandardCharsets.UTF_8) + " {}";
        SnapshotRejected after = assertThrows(SnapshotRejected.class, () -> read("two.json", trailing));
        assertTrue(after.getMessage().contains("after the snapshot"), after.getMessage());

        assertThrows(SnapshotRejected.class, () -> reader.read("empty.json", new ByteArrayInputStream(new byte[0]), 0));
    }

    @Test
    @DisplayName("a field of an unexpected shape is unreadable, never a number")
    void unexpectedShapes()
    {
        SnapshotJson json = SnapshotJson.at("2026-09-30T08:00:00Z").queue("orders", 1, 5, 5, 0);
        ((ObjectNode) json.rows("queues").get(0)).putArray("messageCount").add(5);
        ((ObjectNode) json.rows("queues").get(0)).remove("messagesAdded");
        json.health().putObject("uptimeMillis").put("surprise", true);

        SnapshotFile file = json.read(reader, "odd.json");

        Value depth = file.queues().data().get("orders").levels().get("messageCount");
        assertFalse(depth.known());
        assertNull(depth.number());
        assertEquals(Value.NOT_IN_FILE, file.queues().data().get("orders").counters().get("messagesAdded").missing());
        assertFalse(file.uptimeMillis().isNumber());
    }

    private SnapshotFile read(String label, String content)
    {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return reader.read(label, new ByteArrayInputStream(bytes), bytes.length);
    }
}
