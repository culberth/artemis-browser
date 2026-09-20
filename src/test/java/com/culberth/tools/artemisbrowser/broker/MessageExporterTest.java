package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Message bodies are arbitrary input from whoever produced them, and they land in a file someone opens in Excel. Both
 * halves of that sentence are why these cases exist.
 */
class MessageExporterTest
{

    private final MessageExporter exporter = new MessageExporter();

    @Test
    @DisplayName("a body containing commas and quotes does not shift the columns")
    void escapesSeparators() throws Exception
    {
        String csv = csv(message("a,b,\"c\""));
        String row = csv.lines().skip(1).findFirst().orElseThrow();

        assertTrue(row.endsWith("\"a,b,\"\"c\"\"\",false"), row);
        // Header plus exactly one data row: an unescaped newline or comma would produce more.
        assertEquals(2, csv.lines().count(), csv);
    }

    @Test
    @DisplayName("a body containing a newline stays inside its own record")
    void escapesNewlines() throws Exception
    {
        String csv = csv(message("line one\nline two"));

        // The embedded newline lives inside a quoted field, so the file still has one data record,
        // even though it spans two physical lines.
        assertTrue(csv.contains("\"line one\nline two\""), csv);
    }

    @Test
    @DisplayName("a body that looks like a formula is neutralised")
    void defusesFormulaInjection() throws Exception
    {
        // Otherwise opening the export in a spreadsheet executes whatever a producer wrote.
        assertTrue(csv(message("=cmd|'/c calc'!A1")).contains("\"'=cmd"), "leading = not defused");
        assertTrue(csv(message("+1234")).contains("\"'+1234\""), "leading + not defused");
        assertTrue(csv(message("@SUM(A1)")).contains("\"'@SUM(A1)\""), "leading @ not defused");
    }

    @Test
    @DisplayName("an ordinary body is not mangled")
    void leavesNormalBodiesAlone() throws Exception
    {
        assertTrue(csv(message("Order payload")).contains("\"Order payload\""));
    }

    @Test
    @DisplayName("a null body exports as an empty field rather than the text 'null'")
    void handlesNullBody() throws Exception
    {
        String csv = csv(new MessageSummary(1, "ID:1", "1", "Text", null, "", 4, true, false, 0, "CORE", false,
                Map.of(), null, false));

        assertTrue(csv.contains("\"\""), csv);
        assertTrue(!csv.contains("null"), csv);
    }

    @Test
    @DisplayName("CSV exposes whether the body was truncated, same as JSON does")
    void csvExposesTruncation() throws Exception
    {
        MessageSummary truncated = new MessageSummary(1, "ID:1", "1", "Text", 0L, "", 4, true, false, 10, "CORE", false,
                Map.of(), "partial", true);
        String row = csv(truncated).lines().skip(1).findFirst().orElseThrow();

        assertTrue(row.endsWith(",true"), row);
    }

    @Test
    @DisplayName("JSON export carries the queue, filter and count alongside the messages")
    void jsonCarriesContext() throws Exception
    {
        StringWriter writer = new StringWriter();
        exporter.writeJson(writer, "orders", "AMQPriority > 4", List.of(message("body")));
        String json = writer.toString();

        assertTrue(json.contains("\"queue\" : \"orders\""), json);
        assertTrue(json.contains("\"filter\" : \"AMQPriority > 4\""), json);
        assertTrue(json.contains("\"count\" : 1"), json);
    }

    @Test
    @DisplayName("a cross-queue CSV names each row's queue, so the rows stay tellable apart")
    void crossQueueCsvKeepsTheQueuePerRow() throws Exception
    {
        StringWriter writer = new StringWriter();
        try (MessageExporter.CrossQueueExport export = exporter.openCrossQueueExport(writer, false, "count = 1"))
        {
            export.write("orders", List.of(message("first")));
            export.write("payments", List.of(message("second")));
        }
        String csv = writer.toString();

        assertEquals(3, csv.lines().count(), csv);
        assertTrue(csv.lines().skip(1).findFirst().orElseThrow().startsWith("\"orders\""), csv);
        assertTrue(csv.lines().skip(2).findFirst().orElseThrow().startsWith("\"payments\""), csv);
        // One header, however many queues were written.
        assertEquals(1, csv.lines().filter(line -> line.startsWith("queue,")).count(), csv);
    }

    @Test
    @DisplayName("a cross-queue JSON groups messages under their queue and counts the lot")
    void crossQueueJsonGroupsByQueue() throws Exception
    {
        StringWriter writer = new StringWriter();
        try (MessageExporter.CrossQueueExport export = exporter.openCrossQueueExport(writer, true, "count = 1"))
        {
            export.write("orders", List.of(message("first"), message("second")));
            export.write("payments", List.of(message("third")));
        }
        JsonNode json = new ObjectMapper().readTree(writer.toString());

        assertEquals("count = 1", json.get("filter").asString());
        assertEquals(2, json.get("queues").size());
        assertEquals("orders", json.get("queues").get(0).get("queue").asString());
        assertEquals(2, json.get("queues").get(0).get("messages").size());
        assertEquals(3, json.get("count").asInt());
    }

    @Test
    @DisplayName("a cross-queue export that matched nothing is still a readable document")
    void crossQueueExportOfNothingIsStillValid() throws Exception
    {
        StringWriter csv = new StringWriter();
        exporter.openCrossQueueExport(csv, false, "nope = 1").close();

        StringWriter json = new StringWriter();
        exporter.openCrossQueueExport(json, true, "nope = 1").close();

        assertEquals(1, csv.toString().lines().count(), csv.toString());
        assertEquals(0, new ObjectMapper().readTree(json.toString()).get("count").asInt());
    }

    @Test
    @DisplayName("a queue name with a quote in it cannot break out of the JSON")
    void crossQueueJsonEscapesTheQueueName() throws Exception
    {
        StringWriter writer = new StringWriter();
        try (MessageExporter.CrossQueueExport export = exporter.openCrossQueueExport(writer, true, "a \"filter\""))
        {
            export.write("odd\"name", List.of(message("body")));
        }
        JsonNode json = new ObjectMapper().readTree(writer.toString());

        assertEquals("odd\"name", json.get("queues").get(0).get("queue").asString());
        assertEquals("a \"filter\"", json.get("filter").asString());
    }

    @Test
    @DisplayName("properties travel with the export, flattened into one cell")
    void csvCarriesProperties() throws Exception
    {
        MessageSummary message = new MessageSummary(1, "ID:1", "1", "Text", 0L, "", 4, true, false, 10, "CORE", false,
                Map.of("orderRef", "A-17"), "body", false);

        String csv = csv(message);

        assertTrue(csv.lines().findFirst().orElseThrow().contains("properties"), csv);
        assertTrue(csv.contains("\"orderRef=A-17\""), csv);
    }

    @Test
    @DisplayName("a property value containing a comma cannot shift the columns")
    void csvQuotesAwkwardPropertyValues() throws Exception
    {
        MessageSummary message = new MessageSummary(1, "ID:1", "1", "Text", 0L, "", 4, true, false, 10, "CORE", false,
                Map.of("note", "a,b"), "body", false);

        String csv = csv(message);

        assertTrue(csv.contains("\"note=a,b\""), csv);
        assertEquals(2, csv.lines().count(), csv);
    }

    private String csv(MessageSummary message) throws Exception
    {
        StringWriter writer = new StringWriter();
        exporter.writeCsv(writer, "orders", List.of(message));
        return writer.toString();
    }

    private MessageSummary message(String body)
    {
        return new MessageSummary(1, "ID:1", "1", "Text", 0L, "", 4, true, false, 10, "CORE", false, Map.of(), body,
                false);
    }
}
