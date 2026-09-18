package com.culberth.tools.artemisbrowser.broker;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.ObjectMessage;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.openmbean.CompositeData;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads messages off a queue without removing them, by two different routes.
 *
 * <p>
 * <b>The paged list</b> uses Artemis's management {@code browse(page, pageSize, filter)}. The broker does the paging,
 * so opening page 400 of a 50,000-message dead-letter queue does not drag the preceding 20,000 messages through this
 * process — which is exactly what a client-side {@code QueueBrowser} that skips would do.
 *
 * <p>
 * <b>The single-message detail</b> uses a JMS {@link QueueBrowser} with a {@code JMSMessageID} selector. Management
 * browse only exposes a {@code text} body, so bytes, map and stream messages would show nothing; the selector pushes
 * the lookup to the broker, so this stays a one-message read rather than a scan.
 *
 * <p>
 * Both are non-destructive: nothing is consumed, acknowledged, or moved into the delivering state. That is verified
 * against a real broker, not assumed.
 */
@Service
public class QueueBrowseService
{

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /** Artemis message type codes, from org.apache.activemq.artemis.api.core.Message. */
    private static final Map<Integer, String> TYPE_NAMES = Map.of(0, "Bytes", 2, "Object", 3, "Text", 4, "Bytes", 5,
            "Map", 6, "Stream");

    private final BrokerSession brokerSession;
    private final int bodyPreviewChars;
    private final int bodyDetailChars;

    public QueueBrowseService(BrokerSession brokerSession,
            @Value("${artemis.body-preview-chars:200}") int bodyPreviewChars,
            @Value("${artemis.body-detail-chars:200000}") int bodyDetailChars)
    {
        this.brokerSession = brokerSession;
        this.bodyPreviewChars = bodyPreviewChars;
        this.bodyDetailChars = bodyDetailChars;
    }

    /**
     * One page of messages matching an optional Artemis core filter.
     *
     * @param filter Artemis <em>core</em> filter syntax ({@code AMQPriority > 4}), not a JMS selector. A JMS-style name
     *               is not rejected — it simply matches nothing — so the UI must say which dialect it is asking for.
     */
    public MessagePage page(String queueName, String filter, int page, int pageSize)
    {
        ManagementChannel management = brokerSession.requireManagement();
        String resource = ResourceNames.QUEUE + queueName;
        String effectiveFilter = filter == null ? "" : filter.trim();

        long total = asLong(management.invoke(resource, "countMessages", effectiveFilter));

        Object result = effectiveFilter.isEmpty() ? management.invoke(resource, "browse", page, pageSize)
                : management.invoke(resource, "browse", page, pageSize, effectiveFilter);

        List<MessageSummary> messages = new ArrayList<>();
        long firstPosition = (long) (page - 1) * pageSize + 1;
        for (CompositeData entry : compositeData(result))
        {
            messages.add(toSummary(entry, firstPosition + messages.size()));
        }
        return new MessagePage(queueName, effectiveFilter, page, pageSize, total, messages);
    }

    /** One message in full, or null when it is no longer on the queue. */
    public MessageDetail detail(String queueName, String browseName, String messageId)
    {
        Session session = brokerSession.requireSession();
        synchronized (this)
        {
            // Quoting: JMS string literals use doubled single-quotes to escape. Message IDs are
            // broker-generated so this is belt-and-braces, but a selector built by concatenation
            // without it is a injection-shaped hole.
            String selector = "JMSMessageID = '" + messageId.replace("'", "''") + "'";
            try (QueueBrowser browser = session.createBrowser(session.createQueue(browseName), selector))
            {
                Enumeration<?> enumeration = browser.getEnumeration();
                if (!enumeration.hasMoreElements())
                {
                    return null;
                }
                return toDetail(queueName, (Message) enumeration.nextElement());
            }
            catch (JMSException e)
            {
                throw new BrokerException(
                        "Could not read message " + messageId + " from '" + queueName + "': " + e.getMessage(), e);
            }
        }
    }

    private CompositeData[] compositeData(Object result)
    {
        // browse() answers a Map whose single key is the literal string
        // "javax.management.openmbean.CompositeData"; the value is the array we want.
        if (result instanceof Map<?, ?> map)
        {
            for (Object value : map.values())
            {
                if (value instanceof CompositeData[] array)
                {
                    return array;
                }
            }
        }
        if (result instanceof CompositeData[] array)
        {
            return array;
        }
        return new CompositeData[0];
    }

    private MessageSummary toSummary(CompositeData data, long position)
    {
        Long timestamp = asLongOrNull(get(data, "timestamp"));
        String body = asString(get(data, "text"));
        if (body == null)
        {
            body = "(no text body — open the message to read it)";
        }
        boolean truncated = body.length() > bodyPreviewChars;

        return new MessageSummary(position, asString(get(data, "userID")), asString(get(data, "messageID")),
                TYPE_NAMES.getOrDefault((int) asLong(get(data, "type")), "Message"), timestamp,
                timestamp == null ? "" : TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestamp)),
                (int) asLong(get(data, "priority")), asBoolean(get(data, "durable")),
                asBoolean(get(data, "redelivered")), asLong(get(data, "persistentSize")),
                asString(get(data, "protocol")), truncated ? body.substring(0, bodyPreviewChars) : body, truncated);
    }

    private MessageDetail toDetail(String queueName, Message message) throws JMSException
    {
        String body = bodyOf(message);
        boolean truncated = body != null && body.length() > bodyDetailChars;
        long expiration = message.getJMSExpiration();

        return new MessageDetail(queueName, message.getJMSMessageID(), message.getJMSCorrelationID(), typeOf(message),
                message.getJMSDestination() == null ? "" : message.getJMSDestination().toString(),
                message.getJMSTimestamp() == 0 ? ""
                        : TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(message.getJMSTimestamp())),
                expiration == 0 ? "never" : TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(expiration)),
                message.getJMSPriority(), message.getJMSDeliveryMode() == jakarta.jms.DeliveryMode.PERSISTENT,
                message.getJMSRedelivered(), message.getLongProperty("JMSXDeliveryCount"),
                message.getStringProperty("JMSXGroupID"), truncated ? body.substring(0, bodyDetailChars) : body,
                truncated, properties(message));
    }

    private String typeOf(Message message)
    {
        if (message instanceof TextMessage)
        {
            return "Text";
        }
        else if (message instanceof BytesMessage)
        {
            return "Bytes";
        }
        else if (message instanceof MapMessage)
        {
            return "Map";
        }
        else if (message instanceof ObjectMessage)
        {
            return "Object";
        }
        else if (message instanceof StreamMessage)
        {
            return "Stream";
        }
        return "Message";
    }

    private String bodyOf(Message message) throws JMSException
    {
        if (message instanceof TextMessage text)
        {
            return text.getText() == null ? "" : text.getText();
        }
        if (message instanceof BytesMessage bytes)
        {
            return bytesBody(bytes);
        }
        if (message instanceof MapMessage map)
        {
            StringBuilder builder = new StringBuilder();
            Enumeration<?> names = map.getMapNames();
            while (names.hasMoreElements())
            {
                String name = names.nextElement().toString();
                builder.append(name).append(" = ").append(map.getObject(name)).append('\n');
            }
            return builder.toString();
        }
        if (message instanceof ObjectMessage)
        {
            // Deliberately NOT getObject(): that deserializes whatever a producer put on the queue,
            // inside this process. A read-only browser must never do that — it is the classic
            // untrusted-deserialization foothold, and the payload class usually is not on our
            // classpath anyway.
            return "(object message — body not deserialized)";
        }
        if (message instanceof StreamMessage)
        {
            return "(stream message — body not read)";
        }
        return "(no body)";
    }

    private String bytesBody(BytesMessage bytes) throws JMSException
    {
        long length = bytes.getBodyLength();
        int take = (int) Math.min(length, bodyDetailChars);
        byte[] buffer = new byte[take];
        bytes.reset();
        bytes.readBytes(buffer, take);
        bytes.reset();

        String asText = new String(buffer, StandardCharsets.UTF_8);
        boolean printable = asText.chars().noneMatch(c -> c < 0x09 || (c > 0x0D && c < 0x20));
        return length + " bytes\n" + (printable ? asText : hex(buffer));
    }

    private String hex(byte[] buffer)
    {
        StringBuilder builder = new StringBuilder(buffer.length * 3);
        for (byte b : buffer)
        {
            builder.append(String.format("%02x ", b));
        }
        return builder.toString().trim();
    }

    private Map<String, String> properties(Message message) throws JMSException
    {
        Map<String, String> properties = new LinkedHashMap<>();
        Enumeration<?> names = message.getPropertyNames();
        while (names.hasMoreElements())
        {
            String name = names.nextElement().toString();
            Object value = message.getObjectProperty(name);
            properties.put(name, value == null ? "" : value.toString());
        }
        return properties;
    }

    private Object get(CompositeData data, String key)
    {
        return data.getCompositeType().keySet().contains(key) ? data.get(key) : null;
    }

    private long asLong(Object value)
    {
        if (value instanceof Number number)
        {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank())
        {
            try
            {
                return Long.parseLong(text.trim());
            }
            catch (NumberFormatException ignored)
            {
                return 0L;
            }
        }
        return 0L;
    }

    private Long asLongOrNull(Object value)
    {
        long parsed = asLong(value);
        return parsed == 0 ? null : parsed;
    }

    private boolean asBoolean(Object value)
    {
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private String asString(Object value)
    {
        return value == null ? null : String.valueOf(value);
    }
}
