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
