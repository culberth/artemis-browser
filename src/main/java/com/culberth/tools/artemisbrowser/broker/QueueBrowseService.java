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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads messages off a queue without removing them.
 *
 * <p>This uses a JMS {@link QueueBrowser}, which is the one read path the JMS spec guarantees is
 * non-destructive: it hands out copies and never acknowledges, so nothing is consumed, nothing is
 * marked delivered, and no redelivery counter moves. A {@code MessageConsumer} — even one that
 * never acknowledges — would put messages into the delivering state and is not an option here.
 */
@Service
public class QueueBrowseService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final BrokerSession brokerSession;
    private final int bodyPreviewChars;

    public QueueBrowseService(
            BrokerSession brokerSession,
            @Value("${artemis.body-preview-chars:2000}") int bodyPreviewChars) {
        this.brokerSession = brokerSession;
        this.bodyPreviewChars = bodyPreviewChars;
    }

    public BrowseResult browse(String queueName, String browseName, int limit) {
        Session session = brokerSession.requireSession();
        List<MessageSummary> messages = new ArrayList<>();
        boolean more = false;

        synchronized (this) {
            try (QueueBrowser browser = session.createBrowser(session.createQueue(browseName))) {
                Enumeration<?> enumeration = browser.getEnumeration();
                int position = 0;
                while (enumeration.hasMoreElements()) {
                    Message message = (Message) enumeration.nextElement();
                    if (position >= limit) {
                        more = true;
                        break;
                    }
                    messages.add(summarise(message, ++position));
                }
            } catch (JMSException e) {
                throw new BrokerException("Could not browse '" + queueName + "': " + e.getMessage(), e);
            }
        }
        return new BrowseResult(queueName, limit, more, messages);
    }

    private MessageSummary summarise(Message message, int position) throws JMSException {
        String body = bodyPreview(message);
        boolean truncated = body != null && body.length() > bodyPreviewChars;
        return new MessageSummary(
                position,
                message.getJMSMessageID(),
                message.getJMSCorrelationID(),
                typeOf(message),
                message.getJMSTimestamp() == 0 ? null : message.getJMSTimestamp(),
                formatTimestamp(message.getJMSTimestamp()),
                message.getJMSPriority(),
                message.getJMSDeliveryMode() == jakarta.jms.DeliveryMode.PERSISTENT,
                message.getJMSRedelivered(),
                message.getJMSExpiration() == 0 ? null : message.getJMSExpiration(),
                message.getStringProperty("JMSXGroupID"),
                truncated ? body.substring(0, bodyPreviewChars) : body,
                truncated,
                properties(message));
    }

    private String formatTimestamp(long epochMillis) {
        if (epochMillis == 0) {
            return "";
        }
        return TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private String typeOf(Message message) {
        if (message instanceof TextMessage) {
            return "Text";
        } else if (message instanceof BytesMessage) {
            return "Bytes";
        } else if (message instanceof MapMessage) {
            return "Map";
        } else if (message instanceof ObjectMessage) {
            return "Object";
        } else if (message instanceof StreamMessage) {
            return "Stream";
        }
        return "Message";
    }

    private String bodyPreview(Message message) throws JMSException {
        if (message instanceof TextMessage text) {
            return text.getText();
        }
        if (message instanceof BytesMessage bytes) {
            return bytesPreview(bytes);
        }
        if (message instanceof MapMessage map) {
            StringBuilder builder = new StringBuilder();
            Enumeration<?> names = map.getMapNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement().toString();
                builder.append(name).append(" = ").append(map.getObject(name)).append('\n');
            }
            return builder.toString();
        }
        if (message instanceof ObjectMessage) {
            // Deliberately NOT getObject(): that deserializes whatever a producer put on the queue,
            // inside this process. A read-only browser must never do that — it is the classic
            // untrusted-deserialization foothold, and the payload class usually is not on our
            // classpath anyway.
            return "(object message — body not deserialized)";
        }
        if (message instanceof StreamMessage) {
            return "(stream message — body not read)";
        }
        return "(no body)";
    }

    private String bytesPreview(BytesMessage bytes) throws JMSException {
        long length = bytes.getBodyLength();
        int take = (int) Math.min(length, bodyPreviewChars);
        byte[] buffer = new byte[take];
        bytes.reset();
        bytes.readBytes(buffer, take);
        bytes.reset();

        String asText = new String(buffer, StandardCharsets.UTF_8);
        boolean printable = asText.chars().noneMatch(c -> c < 0x09 || (c > 0x0D && c < 0x20));
        String rendered = printable ? asText : hex(buffer);
        return length + " bytes\n" + rendered;
    }

    private String hex(byte[] buffer) {
        StringBuilder builder = new StringBuilder(buffer.length * 3);
        for (byte b : buffer) {
            builder.append(String.format("%02x ", b));
        }
        return builder.toString().trim();
    }

    private Map<String, String> properties(Message message) throws JMSException {
        Map<String, String> properties = new LinkedHashMap<>();
        Enumeration<?> names = message.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement().toString();
            Object value = message.getObjectProperty(name);
            properties.put(name, value == null ? "" : value.toString());
        }
        return properties;
    }
}
