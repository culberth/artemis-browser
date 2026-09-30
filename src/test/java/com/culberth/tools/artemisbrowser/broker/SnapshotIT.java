package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An incident snapshot of a real broker: every section collected through the app's own read paths, written out and read
 * back, and nothing on the broker changed by collecting it.
 */
class SnapshotIT
{

    private static BrokerSession brokerSession;
    private static QueueDirectory queues;
    private static SnapshotService service;

    @BeforeAll
    static void connect() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        queues = new QueueDirectory(brokerSession);
        BrokerInfoService info = new BrokerInfoService(brokerSession);
        AddressDirectory addresses = new AddressDirectory(brokerSession, queues);
        RateService rates = new RateService(brokerSession, new RateTracker(), queues);
        StuckDiagnosisService diagnosis = new StuckDiagnosisService(queues, addresses, info,
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L),
                new DivertDirectory(brokerSession), new InFlightService(brokerSession, info, 5000), rates,
                new ConnectivityService(brokerSession));
        service = new SnapshotService(brokerSession, info, queues, addresses, rates, diagnosis,
                new ConnectivityService(brokerSession), 1000, 200);
    }

    @AfterAll
    static void disconnect()
    {
        if (brokerSession != null)
        {
            brokerSession.close();
        }
    }

    @Test
    @DisplayName("every section is collected from a real broker, and the JSON reads back complete")
    void collectsEverySection() throws Exception
    {
        IncidentSnapshot snapshot = service.collect();

        assertTrue(snapshot.queues().available(), snapshot.queues().explained());
        assertTrue(snapshot.addresses().available(), snapshot.addresses().explained());
        assertTrue(snapshot.diagnosis().available(), snapshot.diagnosis().explained());
        assertTrue(snapshot.connections().rows().available());
        assertFalse(snapshot.addressSettings().isEmpty());
        assertTrue(snapshot.health().version().available());

        StringWriter out = new StringWriter();
        SnapshotWriter.writeJson(out, snapshot);
        JsonNode json = new ObjectMapper().readTree(out.toString());
        assertEquals(IncidentSnapshot.SCHEMA_VERSION, json.get("schemaVersion").asInt());
        JsonNode orders = null;
        for (JsonNode queue : json.get("sections").get("queues").get("data"))
        {
            if ("it-orders".equals(queue.get("name").asText()))
            {
                orders = queue;
            }
        }
        assertTrue(orders != null && orders.get("id").asLong() >= 0, "queues carry their broker id");
        assertFalse(json.get("sections").get("trends").get("readings").isEmpty(),
                "the snapshot's own queue listing is a trend reading");

        StringWriter text = new StringWriter();
        SnapshotWriter.writeText(new PrintWriter(text, true), snapshot);
        assertTrue(text.toString().contains("it-orders"), "the busiest queues are in the summary");
    }

    @Test
    @DisplayName("collecting a snapshot consumes, acknowledges and removes nothing")
    void changesNothing()
    {
        Map<String, List<Long>> before = counters();
        service.collect();
        assertEquals(before, counters());
    }

    private static Map<String, List<Long>> counters()
    {
        return queues.overview().stream().filter(queue -> queue.name().startsWith("it-"))
                .collect(Collectors.toMap(QueueOverview::name, queue -> List.of(queue.messageCount(),
                        queue.deliveringCount(), queue.messagesAcked(), queue.messagesAdded())));
    }
}
