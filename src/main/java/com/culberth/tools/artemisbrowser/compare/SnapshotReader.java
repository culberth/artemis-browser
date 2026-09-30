package com.culberth.tools.artemisbrowser.compare;

import com.culberth.tools.artemisbrowser.broker.IncidentSnapshot;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a saved incident snapshot back, treating the file as untrusted.
 *
 * <p>
 * Bounded before anything is built from it: at most {@code artemis.compare.max-file-bytes} of JSON, nested at most
 * {@value #MAX_DEPTH} deep, no string longer than {@value #MAX_STRING} characters, no duplicate keys, and at most
 * {@code artemis.compare.max-rows} rows in any one section. The trends section — the largest part of a long session's
 * snapshot, and nothing a comparison reads — is skipped while streaming rather than held. Nothing here connects to a
 * broker: a comparison is two files and nothing else.
 *
 * <p>
 * Rejected, with a sentence saying why: anything that is not JSON, not an artemis-browser incident snapshot (the text
 * summary included), of a schema version this build does not read, without a collection time, or over a limit.
 */
@Component
public class SnapshotReader
{

    static final String KIND = "artemis-browser incident snapshot";

    /** The schema versions this build can compare. A new version is added here only with a test that reads it. */
    static final Set<Integer> SUPPORTED_SCHEMAS = Set.of(IncidentSnapshot.SCHEMA_VERSION);

    static final int MAX_DEPTH = 64;
    static final int MAX_STRING = 1_000_000;

    private final long maxBytes;
    private final int maxRows;
    private final JsonMapper mapper;

    public SnapshotReader(
            @org.springframework.beans.factory.annotation.Value("${artemis.compare.max-file-bytes:67108864}") long maxBytes,
            @org.springframework.beans.factory.annotation.Value("${artemis.compare.max-rows:50000}") int maxRows)
    {
        this.maxBytes = Math.max(1, maxBytes);
        this.maxRows = Math.max(1, maxRows);
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(this.maxBytes)
                        .maxNestingDepth(MAX_DEPTH).maxStringLength(MAX_STRING).maxNameLength(10_000).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        // Sections are read one value at a time from a parser that has more to come; the reader checks
        // for content after the closing brace itself.
        this.mapper = JsonMapper.builder(factory).disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

    public long maxBytes()
    {
        return maxBytes;
    }

    public int maxRows()
    {
        return maxRows;
    }

    /**
     * @param label what to call the file in messages — its name as uploaded
     * @param size  its size in bytes where known, else -1; checked before reading a byte
     */
    public SnapshotFile read(String label, InputStream in, long size)
    {
        if (size > maxBytes)
        {
            throw new SnapshotRejected(label + " is " + size + " bytes, over the " + maxBytes
                    + "-byte limit (artemis.compare.max-file-bytes).");
        }
        if (size == 0)
        {
            throw new SnapshotRejected(label + " is empty.");
        }
        ObjectNode root;
        try
        {
            root = readSkippingTrends(label, in);
        }
        catch (JacksonException e)
        {
            throw new SnapshotRejected(label + " is not a JSON file this tool can read: " + e.getOriginalMessage());
        }
        return interpret(label, root);
    }

    /**
     * The whole document as a tree, except {@code sections.trends}, which is streamed past. Only an object at the top
     * is accepted, and nothing may follow it.
     */
    private ObjectNode readSkippingTrends(String label, InputStream in)
    {
        try (JsonParser parser = mapper.createParser(in))
        {
            if (parser.nextToken() != JsonToken.START_OBJECT)
            {
                throw new SnapshotRejected(label + " is not a JSON object, so not an incident snapshot.");
            }
            ObjectNode root = mapper.createObjectNode();
            while (parser.nextToken() == JsonToken.PROPERTY_NAME)
            {
                String name = parser.currentName();
                parser.nextToken();
                if ("sections".equals(name) && parser.currentToken() == JsonToken.START_OBJECT)
                {
                    ObjectNode sections = root.putObject("sections");
                    while (parser.nextToken() == JsonToken.PROPERTY_NAME)
                    {
                        String section = parser.currentName();
                        parser.nextToken();
                        if ("trends".equals(section))
                        {
                            parser.skipChildren();
                            sections.putObject("trends").put("skipped", true);
                        }
                        else
                        {
                            sections.set(section, (JsonNode) mapper.readTree(parser));
                        }
                    }
                }
                else
                {
                    root.set(name, (JsonNode) mapper.readTree(parser));
                }
            }
            if (parser.nextToken() != null)
            {
                throw new SnapshotRejected(label + " has content after the snapshot's closing brace.");
            }
            return root;
        }
    }

    private SnapshotFile interpret(String label, ObjectNode root)
    {
        if (!KIND.equals(root.path("kind").asString("")))
        {
            throw new SnapshotRejected(label + " is not an artemis-browser incident snapshot."
                    + " Only the JSON download can be compared; the text summary cannot.");
        }
        JsonNode version = root.path("schemaVersion");
        if (!version.isIntegralNumber() || !version.canConvertToInt())
        {
            throw new SnapshotRejected(label + " has no schema version, so what its fields mean is unknown.");
        }
        if (!SUPPORTED_SCHEMAS.contains(version.asInt()))
        {
            throw new SnapshotRejected(label + " uses snapshot schema " + version.asInt()
                    + "; this build compares schema "
                    + SUPPORTED_SCHEMAS.stream().sorted().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
                    + " only. Its fields may mean something else, so it is not compared.");
        }
        JsonNode collection = root.path("collection");
        Instant started = time(collection.path("startedAt"));
        if (started == null)
        {
            throw new SnapshotRejected(label + " does not say when it was collected, so it cannot be put in order.");
        }
        Instant finished = time(collection.path("finishedAt"));

        JsonNode sections = root.path("sections");
        JsonNode healthNode = sections.path("health");
        Map<String, Value> health = values(label, "health", healthNode.path("data"));

        JsonNode connection = root.path("connection");
        return new SnapshotFile(label, version.asInt(), started, finished == null ? started : finished,
                connection.path("host").asString(""), connection.path("port").asString(""),
                time(healthNode.path("collectedAt")), health, queues(label, sections.path("queues")),
                addresses(label, sections.path("addresses")), settings(label, sections.path("addressSettings")),
                listing(label, "consumers", sections.path("consumers"), SnapshotReader::consumer),
                listing(label, "connections", sections.path("connections"), SnapshotReader::connection),
                diagnosis(label, sections.path("diagnosis")), lines(label, root.path("unavailable"), true),
                lines(label, root.path("omitted"), false), values(label, "limits", root.path("limits")));
    }

    // ---------------------------------------------------------------- sections

    private SnapshotFile.Section<Map<String, SnapshotFile.QueueRow>> queues(String label, JsonNode node)
    {
        return rows(label, "queues", node, row ->
        {
            String name = row.path("name").asString("");
            if (name.isEmpty() || row.path("thisTool").asBoolean(false))
            {
                return null;
            }
            Map<String, Value> levels = pick(row, "messageCount", "deliveringCount", "scheduledCount", "consumerCount",
                    "paused");
            Map<String, Value> counters = pick(row, "messagesAdded", "messagesAcknowledged", "messagesExpired",
                    "messagesKilled");
            Map<String, Value> configuration = values(label, "queues." + name, row.path("configuration"));
            return Map.entry(name,
                    new SnapshotFile.QueueRow(name, Value.from(row.get("id")), row.path("address").asString(""),
                            row.path("internal").asBoolean(false), levels, counters, configuration));
        });
    }

    private SnapshotFile.Section<Map<String, SnapshotFile.AddressRow>> addresses(String label, JsonNode node)
    {
        return rows(label, "addresses", node, row ->
        {
            String name = row.path("name").asString("");
            if (name.isEmpty())
            {
                return null;
            }
            return Map.entry(name, new SnapshotFile.AddressRow(name, row.path("routingTypes").asString(""),
                    row.path("internal").asBoolean(false), pick(row, "messageCount", "addressSizeBytes", "pages")));
        });
    }

    private Map<String, SnapshotFile.Section<Map<String, Value>>> settings(String label, JsonNode node)
    {
        Map<String, SnapshotFile.Section<Map<String, Value>>> settings = new LinkedHashMap<>();
        JsonNode data = node.path("data");
        if (!data.isObject())
        {
            return settings;
        }
        count(label, "addressSettings", data.size());
        for (Map.Entry<String, JsonNode> entry : data.properties())
        {
            JsonNode value = entry.getValue();
            if (value.path("unavailable").isString())
            {
                settings.put(entry.getKey(), SnapshotFile.Section.missing(value.path("unavailable").asString(),
                        value.path("detail").asString(""), null));
            }
            else
            {
                settings.put(entry.getKey(),
                        SnapshotFile.Section.of(values(label, "addressSettings." + entry.getKey(), value), null));
            }
        }
        return settings;
    }

    private <T> SnapshotFile.Listing<T> listing(String label, String name, JsonNode node,
            Function<JsonNode, Map.Entry<String, T>> row)
    {
        SnapshotFile.Section<Map<String, T>> rows = rows(label, name, node, row);
        long total = node.path("total").canConvertToLong() ? node.path("total").asLong() : 0;
        return new SnapshotFile.Listing<>(rows, total, node.path("truncated").asBoolean(false));
    }

    private static Map.Entry<String, SnapshotFile.ConsumerRow> consumer(JsonNode row)
    {
        if (row.path("thisTool").asBoolean(false))
        {
            return null;
        }
        String key = row.path("connectionId").asString("") + ":" + row.path("sessionId").asString("") + ":"
                + row.path("consumerId").asString("");
        return Map.entry(key, new SnapshotFile.ConsumerRow(key, row.path("queue").asString(""),
                Value.from(row.get("deliveringCount"))));
    }

    private static Map.Entry<String, SnapshotFile.ConnectionRow> connection(JsonNode row)
    {
        String id = row.path("connectionId").asString("");
        if (id.isEmpty() || row.path("thisTool").asBoolean(false))
        {
            return null;
        }
        return Map.entry(id, new SnapshotFile.ConnectionRow(id, row.path("clientAddress").asString(""),
                Value.from(row.get("sessionCount"))));
    }

    private SnapshotFile.Section<SnapshotFile.Diagnosis> diagnosis(String label, JsonNode node)
    {
        SnapshotFile.Section<SnapshotFile.Diagnosis> missing = missing(node);
        if (missing != null)
        {
            return missing;
        }
        JsonNode findings = node.path("findings");
        if (!findings.isArray())
        {
            return SnapshotFile.Section.missing("not in this snapshot", "", time(node.path("collectedAt")));
        }
        count(label, "diagnosis", findings.size());
        List<SnapshotFile.FindingRow> rows = new ArrayList<>();
        for (JsonNode f : findings)
        {
            rows.add(new SnapshotFile.FindingRow(text(f, "severity"), text(f, "basis"), text(f, "title"),
                    text(f, "detail"), text(f, "queue"), text(f, "address"), text(f, "clientId"),
                    text(f, "connectionId")));
        }
        List<String> unchecked = new ArrayList<>();
        JsonNode could = node.path("couldNotCheck");
        if (could.isArray())
        {
            count(label, "diagnosis.couldNotCheck", could.size());
            could.forEach(line -> unchecked.add(line.asString("")));
        }
        return SnapshotFile.Section.of(new SnapshotFile.Diagnosis(rows, unchecked), time(node.path("collectedAt")));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A section's rows by name, or why it has none. A row the mapper declines — this tool's own, or one with no name —
     * is left out; a name seen twice keeps its first row.
     */
    private <T> SnapshotFile.Section<Map<String, T>> rows(String label, String name, JsonNode node,
            Function<JsonNode, Map.Entry<String, T>> row)
    {
        SnapshotFile.Section<Map<String, T>> missing = missing(node);
        if (missing != null)
        {
            return missing;
        }
        JsonNode data = node.path("data");
        if (!data.isArray())
        {
            return SnapshotFile.Section.missing("not in this snapshot", "", time(node.path("collectedAt")));
        }
        count(label, name, data.size());
        Map<String, T> rows = new LinkedHashMap<>();
        for (JsonNode item : data)
        {
            Map.Entry<String, T> entry = item.isObject() ? row.apply(item) : null;
            if (entry != null)
            {
                rows.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return SnapshotFile.Section.of(rows, time(node.path("collectedAt")));
    }

    /** Null when the section has data; else the section as missing, with the snapshot's reason or "not in it". */
    private static <T> SnapshotFile.Section<T> missing(JsonNode node)
    {
        Instant at = time(node.path("collectedAt"));
        if (!node.isObject())
        {
            return SnapshotFile.Section.missing("not in this snapshot", "", null);
        }
        JsonNode data = node.path("data");
        if (data.isObject() && data.path("unavailable").isString())
        {
            return SnapshotFile.Section.missing(data.path("unavailable").asString(), data.path("detail").asString(""),
                    at);
        }
        return null;
    }

    private Map<String, Value> values(String label, String where, JsonNode node)
    {
        Map<String, Value> values = new LinkedHashMap<>();
        if (!node.isObject())
        {
            return values;
        }
        count(label, where, node.size());
        for (Map.Entry<String, JsonNode> field : node.properties())
        {
            values.put(field.getKey(), Value.from(field.getValue()));
        }
        return values;
    }

    private static Map<String, Value> pick(JsonNode row, String... fields)
    {
        Map<String, Value> values = new LinkedHashMap<>();
        for (String field : fields)
        {
            values.put(field, Value.from(row.get(field)));
        }
        return values;
    }

    private List<String> lines(String label, JsonNode list, boolean unavailable)
    {
        List<String> lines = new ArrayList<>();
        if (!list.isArray())
        {
            return lines;
        }
        count(label, unavailable ? "unavailable" : "omitted", list.size());
        for (JsonNode row : list)
        {
            String where = row.path("section").asString("")
                    + (row.has("item") ? "." + row.path("item").asString("") : "");
            lines.add(
                    unavailable
                            ? where + ": " + row.path("reason").asString("")
                                    + (row.path("detail").asString("").isBlank() ? ""
                                            : " — " + row.path("detail").asString(""))
                            : where + ": " + row.path("count").asString("?") + " " + row.path("why").asString(""));
        }
        return lines;
    }

    private void count(String label, String section, int size)
    {
        if (size > maxRows)
        {
            throw new SnapshotRejected(label + " has " + size + " entries in " + section + ", over the " + maxRows
                    + "-row limit (artemis.compare.max-rows).");
        }
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString("");
    }

    static Instant time(JsonNode node)
    {
        if (node == null || !node.isString())
        {
            return null;
        }
        try
        {
            return Instant.parse(node.asString());
        }
        catch (DateTimeParseException e)
        {
            return null;
        }
    }
}
