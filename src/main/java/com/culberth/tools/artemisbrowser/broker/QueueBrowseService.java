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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    /** What a row shows when management browse had no {@code text} for it, which is every non-text message. */
    static final String NO_TEXT_BODY = "(no text body — open the message to read it)";

    /**
     * How Artemis signals that it truncated a browsed attribute: it appends {@code ", + N more"} to the value itself
     * rather than reporting the truncation out of band. See {@code JsonUtil.truncate}.
     */
    private static final Pattern BROKER_TRUNCATION = Pattern.compile(", \\+ \\d+ more$");

    /**
     * Shortest body we will accept as broker-truncated. The suffix is ordinary text, so a body that genuinely ends "…,
     * + 3 more" would otherwise be mistaken for a truncated one; the broker's limit
     * ({@code management-message-attribute-size-limit}, 256 by default) is never this small in practice.
     */
    private static final int MIN_BROKER_TRUNCATION_LENGTH = 64;

    /**
     * How a large message announces itself on the JMS read path — Artemis's own {@code Message.HDR_LARGE_BODY_SIZE},
     * carrying the real body size. Management browse has a {@code largeMessage} attribute instead; the two paths do not
     * agree on a spelling, so both are read. Verified against a broker holding a 250KB message.
     */
    private static final String LARGE_BODY_SIZE = "_AMQ_LARGE_SIZE";

    private final BrokerSession brokerSession;
    private final int bodyPreviewChars;
    private final int bodyDetailChars;
    private final int exportBodyScanLimit;
    private final long exportBodyTotalChars;

    public QueueBrowseService(BrokerSession brokerSession,
            @Value("${artemis.body-preview-chars:200}") int bodyPreviewChars,
            @Value("${artemis.body-detail-chars:200000}") int bodyDetailChars,
            @Value("${artemis.export-body-scan-limit:20000}") int exportBodyScanLimit,
            @Value("${artemis.export-body-total-chars:20000000}") long exportBodyTotalChars)
    {
        this.brokerSession = brokerSession;
        this.bodyPreviewChars = bodyPreviewChars;
        this.bodyDetailChars = bodyDetailChars;
        this.exportBodyScanLimit = exportBodyScanLimit;
        this.exportBodyTotalChars = exportBodyTotalChars;
    }

    /**
     * One page of messages matching an optional Artemis core filter.
     *
     * @param filter Artemis <em>core</em> filter syntax ({@code AMQPriority > 4}), not a JMS selector. A JMS-style name
     *               is not rejected — it simply matches nothing — so the UI must say which dialect it is asking for.
     */
    public MessagePage page(String queueName, String filter, int page, int pageSize)
    {
        return page(queueName, filter, page, pageSize, bodyPreviewChars);
    }

    /**
     * Like {@link #page}, but with bodies a download can actually use.
     *
     * <p>
     * Two ceilings sit between a message and a CSV file. Ours is {@code artemis.body-preview-chars}, meant for a table
     * cell — using it here would drop most of every message. The broker's is
     * {@code management-message-attribute-size-limit} (256 characters by default), which truncates the {@code text} of
     * a browse result and appends a literal {@code ", + N more"} <em>to the value</em>; raising our own limit does
     * nothing about it, and an export built on those values ships 256-character bodies carrying that suffix as if they
     * were whole. Management browse also has no body at all for bytes, map or stream messages.
     *
     * <p>
     * So the listing still comes from management browse — the broker does the paging and the core filtering — and then
     * the bodies that need it are filled in from a single JMS browser pass, which reads real bodies of any type.
     *
     * <p>
     * That pass is bounded twice, because it is the one place here that holds real message bodies in memory:
     * {@code artemis.export-body-scan-limit} caps how far it walks, and {@code artemis.export-body-total-chars} caps
     * what it keeps. Whatever it does not reach or cannot afford keeps its management body, marked truncated — a short
     * body is never presented as a whole one.
     *
     * @param browseName the queue's FQQN where it differs from its name; see {@link QueueStats#browseName()}
     */
    public MessagePage pageForExport(String queueName, String browseName, String filter, int page, int pageSize)
    {
        MessagePage listing = page(queueName, filter, page, pageSize, bodyDetailChars);

        Set<String> wanted = listing.messages().stream().filter(this::needsFullBody).map(MessageSummary::messageId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (wanted.isEmpty())
        {
            return listing;
        }

        Map<String, Body> bodies = bodies(browseName, wanted);
        List<MessageSummary> filled = new ArrayList<>(listing.messages().size());
        for (MessageSummary message : listing.messages())
        {
            Body body = message.messageId() == null ? null : bodies.get(message.messageId());
            filled.add(body == null ? message : message.withBody(body.text(), body.truncated()));
        }
        return new MessagePage(listing.queueName(), listing.filter(), listing.page(), listing.pageSize(),
                listing.totalMatching(), filled);
    }

    /** A body management browse either cut short or never had. */
    private boolean needsFullBody(MessageSummary message)
    {
        return message.bodyTruncated() || NO_TEXT_BODY.equals(message.bodyPreview());
    }

    /**
     * Real bodies for a known set of messages, in one browser pass.
     *
     * <p>
     * Deliberately not one {@code JMSMessageID} selector per message the way {@link #detail} does: a selector makes the
     * broker scan the queue, so doing it per message turns an export of n messages into n scans. One pass collects them
     * all, and stops as soon as the last one is found — which for an unfiltered export is within the first page, since
     * those messages are the head of the queue.
     */
    private Map<String, Body> bodies(String browseName, Set<String> messageIds)
    {
        Map<String, Body> bodies = new LinkedHashMap<>();
        Session session = brokerSession.requireSession();
        long budget = exportBodyTotalChars;
        synchronized (this)
        {
            try (QueueBrowser browser = session.createBrowser(session.createQueue(browseName)))
            {
                Enumeration<?> enumeration = browser.getEnumeration();
                int scanned = 0;
                while (enumeration.hasMoreElements() && bodies.size() < messageIds.size()
                        && scanned < exportBodyScanLimit && budget > 0)
                {
                    Message message = (Message) enumeration.nextElement();
                    scanned++;
                    String id = message.getJMSMessageID();
                    if (id != null && messageIds.contains(id))
                    {
                        Body body = read(message, budget);
                        budget -= body.text().length();
                        bodies.put(id, body);
                    }
                }
            }
            catch (JMSException e)
            {
                throw new BrokerException("Could not read message bodies from '" + browseName + "': " + e.getMessage(),
                        e);
            }
        }
        return bodies;
    }

    /**
     * One body, cut to whichever ceiling bites first: the per-message {@code artemis.body-detail-chars}, or what is
     * left of the export's total budget. Either way the row is marked truncated, so a cut body is never presented as a
     * whole one.
     */
    private Body read(Message message, long budget) throws JMSException
    {
        String text = bodyOf(message);
        boolean truncated = false;
        if (text.length() > bodyDetailChars)
        {
            text = text.substring(0, bodyDetailChars);
            truncated = true;
        }
        if (text.length() > budget)
        {
            text = text.substring(0, (int) budget);
            truncated = true;
        }
        return new Body(text, truncated);
    }

    /** A body read over JMS, and whether anything was cut off it on the way. */
    private record Body(String text, boolean truncated)
    {
    }

    private MessagePage page(String queueName, String filter, int page, int pageSize, int previewChars)
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
            messages.add(toSummary(entry, firstPosition + messages.size(), previewChars));
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

    private MessageSummary toSummary(CompositeData data, long position, int previewChars)
    {
        Long timestamp = asLongOrNull(get(data, "timestamp"));
        String text = asString(get(data, "text"));
        boolean truncated = false;
        if (text == null)
        {
            text = NO_TEXT_BODY;
        }
        else
        {
            // The broker's own truncation, which arrives as part of the value: drop the marker and
            // remember that what is left is not the whole body, rather than reporting a 256-character
            // body ending in ", + 4096 more" as complete.
            Matcher marker = BROKER_TRUNCATION.matcher(text);
            if (marker.find() && marker.start() >= MIN_BROKER_TRUNCATION_LENGTH)
            {
                text = text.substring(0, marker.start());
                truncated = true;
            }
        }
        String body = text.length() > previewChars ? text.substring(0, previewChars) : text;
        truncated = truncated || text.length() > previewChars;

        return new MessageSummary(position, asString(get(data, "userID")), asString(get(data, "messageID")),
                TYPE_NAMES.getOrDefault((int) asLong(get(data, "type")), "Message"), timestamp,
                timestamp == null ? "" : TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestamp)),
                (int) asLong(get(data, "priority")), asBoolean(get(data, "durable")),
                asBoolean(get(data, "redelivered")), asLong(get(data, "persistentSize")),
                asString(get(data, "protocol")), asBoolean(get(data, "largeMessage")), body, truncated);
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
                message.getStringProperty("JMSXGroupID"), message.propertyExists(LARGE_BODY_SIZE),
                truncated ? body.substring(0, bodyDetailChars) : body, truncated, properties(message));
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
