package com.culberth.tools.artemisbrowser.broker;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Writes browsed messages out as CSV or JSON. */
@Service
public class MessageExporter
{

    private static final String[] HEADERS =
    { "queue", "position", "messageId", "coreId", "type", "timestamp", "priority", "persistent", "redelivered",
            "sizeBytes", "protocol", "largeMessage", "body", "bodyTruncated"
    };

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void writeCsv(Writer writer, String queueName, List<MessageSummary> messages) throws IOException
    {
        writer.write(String.join(",", HEADERS));
        writer.write("\r\n");
        writeCsvRows(writer, queueName, messages);
        writer.flush();
    }

    /**
     * Streams straight to the writer rather than serializing to a String first: the CSV path already writes row by row,
     * and building the whole document in memory before writing a byte of it would hold a second copy of every body in
     * the export.
     */
    public void writeJson(Writer writer, String queueName, String filter, List<MessageSummary> messages)
            throws IOException
    {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(writer,
                new Export(queueName, filter, messages.size(), messages));
        writer.flush();
    }

    /**
     * A cross-queue export, written one queue at a time.
     *
     * <p>
     * A search can match in several queues, and each queue's bodies have to be fetched separately. Handing the whole
     * result over at once would mean holding every body from every matching queue at once; this way the caller fetches
     * a queue, writes it, and lets it go, so the ceiling is one queue's worth however many queues matched.
     */
    public CrossQueueExport openCrossQueueExport(Writer writer, boolean json, String filter) throws IOException
    {
        return json ? new JsonExport(writer, filter) : new CsvExport(writer);
    }

    /** One cross-queue export in progress. Closing it finishes the document. */
    public interface CrossQueueExport extends AutoCloseable
    {
        void write(String queueName, List<MessageSummary> messages) throws IOException;

        @Override
        void close() throws IOException;
    }

    private final class CsvExport implements CrossQueueExport
    {

        private final Writer writer;

        CsvExport(Writer writer) throws IOException
        {
            this.writer = writer;
            writer.write(String.join(",", HEADERS));
            writer.write("\r\n");
        }

        @Override
        public void write(String queueName, List<MessageSummary> messages) throws IOException
        {
            writeCsvRows(writer, queueName, messages);
        }

        @Override
        public void close() throws IOException
        {
            writer.flush();
        }
    }

    /**
     * Grouped by queue rather than repeating the queue name on every message: a search result is "these queues hold
     * these messages", and that is the shape someone reading the file is looking for.
     */
    private final class JsonExport implements CrossQueueExport
    {

        private final Writer writer;
        private int count;
        private boolean empty = true;

        JsonExport(Writer writer, String filter) throws IOException
        {
            this.writer = writer;
            writer.write("{\n  \"filter\" : ");
            writer.write(objectMapper.writeValueAsString(filter));
            writer.write(",\n  \"queues\" : [");
        }

        @Override
        public void write(String queueName, List<MessageSummary> messages) throws IOException
        {
            writer.write(empty ? "\n    " : ",\n    ");
            empty = false;
            writer.write("{ \"queue\" : ");
            writer.write(objectMapper.writeValueAsString(queueName));
            writer.write(", \"messages\" : ");
            writer.write(objectMapper.writeValueAsString(messages));
            writer.write(" }");
            count += messages.size();
        }

        /** The count lands last because a document written as it goes cannot know it any earlier. */
        @Override
        public void close() throws IOException
        {
            writer.write(empty ? "],\n  \"count\" : " : "\n  ],\n  \"count\" : ");
            writer.write(String.valueOf(count));
            writer.write("\n}\n");
            writer.flush();
        }
    }

    private void writeCsvRows(Writer writer, String queueName, List<MessageSummary> messages) throws IOException
    {
        for (MessageSummary message : messages)
        {
            writer.write(
                    String.join(",", quote(queueName), String.valueOf(message.position()), quote(message.messageId()),
                            quote(message.coreId()), quote(message.type()), quote(message.timestampText()),
                            String.valueOf(message.priority()), String.valueOf(message.persistent()),
                            String.valueOf(message.redelivered()), String.valueOf(message.sizeBytes()),
                            quote(message.protocol()), String.valueOf(message.largeMessage()),
                            quote(message.bodyPreview()), String.valueOf(message.bodyTruncated())));
            writer.write("\r\n");
        }
    }

    /**
     * RFC 4180 quoting, applied to every field rather than only the ones that look dangerous.
     *
     * <p>
     * Message bodies are arbitrary bytes from whoever produced them: a comma, a newline or a quote in a body would
     * otherwise shift every following column, and a leading {@code =} or {@code +} is how a spreadsheet gets talked
     * into evaluating a downloaded file as a formula. The leading apostrophe defuses that without altering what the
     * value reads as.
     */
    private String quote(String value)
    {
        if (value == null)
        {
            return "\"\"";
        }
        String escaped = value.replace("\"", "\"\"");
        if (!escaped.isEmpty() && "=+-@\t\r".indexOf(escaped.charAt(0)) >= 0)
        {
            escaped = "'" + escaped;
        }
        return "\"" + escaped + "\"";
    }

    private record Export(String queue, String filter, int count, List<MessageSummary> messages)
    {
    }
}
