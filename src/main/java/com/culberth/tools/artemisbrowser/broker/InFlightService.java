package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Messages a queue has delivered to a consumer and not yet had acknowledged — the ones {@code browse} cannot see.
 *
 * <p>
 * Read through {@code listDeliveringMessagesAsJSON}, which has no paging and no filter: it returns every in-flight
 * message on the queue in one reply. Measured against 2.44.0 at roughly 280 characters per small message, 1,000
 * messages were 278KB in about 15ms and 100,000 (one consumer, unbounded window) were 28MB in about 800ms. Nothing on
 * the broker side can make that smaller, so the cap is applied before asking: if the queue reports more in flight than
 * {@code artemis.in-flight-limit}, the call is not made.
 *
 * <p>
 * Non-destructive, verified: counters are identical before and after, for CORE, AMQP, STOMP and OpenWire consumers.
 */
@Service
public class InFlightService
{

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /** Artemis message type codes, from org.apache.activemq.artemis.api.core.Message. */
    private static final Map<Integer, String> TYPE_NAMES = Map.of(0, "Bytes", 2, "Object", 3, "Text", 4, "Bytes", 5,
            "Map", 6, "Stream");

    /**
     * The {@code id} inside a {@code ServerConsumer [id=..., filter=..., binding=...]} dump. Lazy up to
     * {@code ", filter="} because the id itself contains colons, and for OpenWire, a host name.
     */
    private static final Pattern CONSUMER_ID = Pattern.compile("^ServerConsumer \\[id=(.+?), filter=");

    /** The message's headers. The reply puts the properties at the same level, so what is not a header is one. */
    private static final Set<String> OWN_FIELDS = Set.of("address", "messageID", "type", "priority", "userID",
            "durable", "expiration", "timestamp");

    /** Artemis's own bookkeeping, which is noise in a list of what a producer set. */
    private static final Set<String> INTERNAL_PROPERTIES = Set.of("__AMQ_CID", "_AMQ_ROUTING_TYPE");

    /** OpenWire's message headers, carried over as properties by the broker's protocol conversion. */
    private static final String OPENWIRE_HEADER_PREFIX = "__HDR_";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BrokerSession brokerSession;
    private final int limit;

    public InFlightService(BrokerSession brokerSession, @Value("${artemis.in-flight-limit:5000}") int limit)
    {
        this.brokerSession = brokerSession;
        this.limit = limit;
    }

    /**
     * The queue's in-flight messages, grouped by the consumer holding them.
     *
     * <p>
     * Takes {@link QueueStats} rather than a name for two reasons: its {@code deliveringCount} decides whether the call
     * is affordable, and its {@code name} is the bare queue name — management resources refuse the FQQN a multicast
     * subscription is browsed by.
     */
    public InFlight inFlight(QueueStats stats)
    {
        if (stats.deliveringCount() <= 0)
        {
            return new InFlight(stats.name(), stats.deliveringCount(), limit, false, false, List.of());
        }
        if (stats.deliveringCount() > limit)
        {
            return new InFlight(stats.name(), stats.deliveringCount(), limit, true, false, List.of());
        }

        Object result = brokerSession.requireManagement().invoke(ResourceNames.QUEUE + stats.name(),
                "listDeliveringMessagesAsJSON");
        try
        {
            return parse(stats, result == null ? "[]" : result.toString());
        }
        catch (BrokerException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new BrokerException(
                    "Could not read the in-flight messages on '" + stats.name() + "': " + e.getMessage(), e);
        }
    }

    private InFlight parse(QueueStats stats, String json)
    {
        JsonNode root = objectMapper.readTree(json);
        List<InFlightConsumer> consumers = new ArrayList<>();
        int kept = 0;
        boolean truncated = false;
        if (root.isArray())
        {
            for (JsonNode entry : root)
            {
                // deliveringCount was read a moment before this reply, and a consumer's window can
                // have filled since, so the limit is enforced on what came back too.
                List<InFlightMessage> messages = new ArrayList<>();
                for (JsonNode element : entry.path("elements"))
                {
                    if (kept >= limit)
                    {
                        truncated = true;
                        break;
                    }
                    messages.add(toMessage(element));
                    kept++;
                }
                consumers.add(consumer(entry.path("consumerName").asString(""), messages));
            }
        }
        return new InFlight(stats.name(), stats.deliveringCount(), limit, false, truncated, consumers);
    }

    static InFlightConsumer consumer(String consumerName, List<InFlightMessage> messages)
    {
        Matcher matcher = CONSUMER_ID.matcher(consumerName);
        if (matcher.find())
        {
            String id = matcher.group(1);
            int first = id.indexOf(':');
            int last = id.lastIndexOf(':');
            if (first > 0 && last > first + 1 && last < id.length() - 1)
            {
                return new InFlightConsumer(consumerName, id.substring(0, first), id.substring(first + 1, last),
                        id.substring(last + 1), messages);
            }
        }
        return new InFlightConsumer(consumerName, null, null, null, messages);
    }

    private InFlightMessage toMessage(JsonNode node)
    {
        long timestamp = node.path("timestamp").asLong(0L);

        Map<String, String> properties = new LinkedHashMap<>();
        node.propertyStream().forEach(field ->
        {
            String name = field.getKey();
            if (!OWN_FIELDS.contains(name) && !INTERNAL_PROPERTIES.contains(name)
                    && !name.startsWith(OPENWIRE_HEADER_PREFIX))
            {
                properties.put(name, field.getValue().asString());
            }
        });

        return new InFlightMessage(node.path("userID").asString(""), node.path("messageID").asLong(0L),
                TYPE_NAMES.getOrDefault(node.path("type").asInt(0), "Message"), node.path("priority").asInt(4),
                node.path("durable").asBoolean(false), timestamp,
                timestamp == 0 ? "" : TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestamp)), properties);
    }
}
