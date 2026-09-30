package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads the broker's own health, acceptors, connections, consumers and producers. All attribute reads. */
@Service
public class BrokerInfoService
{

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /** listConsumers wants a filter document even when there is nothing to filter on. */
    private static final String NO_FILTER = "{\"field\":\"\",\"operation\":\"\",\"value\":\"\"}";

    private static final int LIST_PAGE_SIZE = 200;

    private final BrokerSession brokerSession;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BrokerInfoService(BrokerSession brokerSession)
    {
        this.brokerSession = brokerSession;
    }

    /**
     * One read per attribute, each on its own: an attribute this broker will not return — an older version, or a user
     * that may read some and not others — leaves the rest of the panel standing and says why it is missing. A value in
     * a shape other than the verified one is "could not be read", never 0.
     */
    public BrokerHealth health()
    {
        ManagementChannel management = brokerSession.requireManagement();
        Instant started = Instant.now();
        Reading<Object> status = read(management, "status");
        Reading<JsonNode> server = status.available() ? Reading.attempt(() -> statusServer(status.value()))
                : status.absent();
        if (server.available() && server.value() == null)
        {
            server = Reading.failed("the status attribute has no 'server' section");
        }

        return new BrokerHealth(read(management, "version").map(String::valueOf),
                read(management, "uptime").map(String::valueOf), fromStatus(server, "state"),
                fromStatus(server, "nodeId"), whole(read(management, "connectionCount")),
                whole(read(management, "sessionCount")), whole(read(management, "totalConsumerCount")),
                whole(read(management, "addressMemoryUsage")), whole(read(management, "addressMemoryUsagePercentage")),
                // diskStoreUsage is a 0..1 ratio; maxDiskUsage and diskPressure() work in 0..100.
                fraction(read(management, "diskStoreUsage")).map(ratio -> ratio * 100),
                whole(read(management, "maxDiskUsage")), started, whole(read(management, "globalMaxSize")),
                whole(read(management, "uptimeMillis")));
    }

    private Reading<Object> read(ManagementChannel management, String attribute)
    {
        Reading<Object> reading = Reading.attempt(() -> management.attribute(ResourceNames.BROKER, attribute));
        return reading.available() && reading.value() == null
                ? Reading.failed("the broker returned no value for " + attribute)
                : reading;
    }

    /** A Long, or a string holding one — broker attributes are typed inconsistently across versions. */
    static Reading<Long> whole(Reading<Object> raw)
    {
        if (!raw.available())
        {
            return raw.absent();
        }
        if (raw.value() instanceof Number number)
        {
            return Reading.of(number.longValue());
        }
        try
        {
            return Reading.of(Long.parseLong(raw.value().toString().trim()));
        }
        catch (NumberFormatException e)
        {
            return Reading.failed("'" + raw.value() + "' is not a number");
        }
    }

    static Reading<Double> fraction(Reading<Object> raw)
    {
        if (!raw.available())
        {
            return raw.absent();
        }
        if (raw.value() instanceof Number number)
        {
            return Reading.of(number.doubleValue());
        }
        try
        {
            return Reading.of(Double.parseDouble(raw.value().toString().trim()));
        }
        catch (NumberFormatException e)
        {
            return Reading.failed("'" + raw.value() + "' is not a number");
        }
    }

    public List<AcceptorInfo> acceptors()
    {
        List<AcceptorInfo> acceptors = new ArrayList<>();
        for (JsonNode node : array(invoke("getAcceptorsAsJSON")))
        {
            JsonNode params = node.get("params");
            acceptors.add(new AcceptorInfo(text(node, "name"), params == null ? "" : text(params, "protocols"),
                    params == null ? "" : text(params, "host"),
                    params == null ? 0 : (int) asLong(text(params, "port"))));
        }
        return acceptors;
    }

    public List<BrokerConnection> connections()
    {
        String ourConnection = ourConnectionId();
        List<BrokerConnection> connections = new ArrayList<>();
        for (JsonNode node : array(invoke("listConnectionsAsJSON")))
        {
            String id = text(node, "connectionID");
            connections.add(new BrokerConnection(id, text(node, "clientAddress"), epoch(node.get("creationTime")),
                    (int) asLong(text(node, "sessionCount")), id != null && id.equals(ourConnection)));
        }
        return connections;
    }

    public List<BrokerConsumer> consumers()
    {
        String replyQueue = brokerSession.requireManagement().replyQueueName();
        List<BrokerConsumer> consumers = new ArrayList<>();
        for (JsonNode node : array(invoke("listAllConsumersAsJSON")))
        {
            String queueName = text(node, "queueName");
            consumers.add(new BrokerConsumer(text(node, "consumerID"), queueName, text(node, "connectionID"),
                    text(node, "sessionID"), text(node, "sequentialId"), Boolean.parseBoolean(text(node, "browseOnly")),
                    asLong(text(node, "deliveringCount")), asLong(text(node, "messagesDelivered")),
                    asLong(text(node, "messagesAcknowledged")), text(node, "status"),
                    // This tool's own management reply consumer. Worth labelling rather than
                    // hiding: someone counting consumers on a quiet broker should see why it is 1.
                    queueName != null && queueName.equals(replyQueue)));
        }
        return consumers;
    }

    /**
     * Consumers on the named queues, with their client ids, from {@code listConsumers}.
     *
     * <p>
     * Filtered here rather than by the broker: {@code listConsumers} takes the same filter document as
     * {@code listQueues}, but which fields it accepts has not been checked, and a filter that silently matches nothing
     * would read as "nobody is subscribed". Its shape is mixed — counters quoted, {@code lastDeliveredTime} bare,
     * {@code creationTime} a {@code Date.toString()} — so only the quoted counters are read.
     */
    public List<SubscriberConsumer> consumersOn(Set<String> queueNames)
    {
        List<SubscriberConsumer> consumers = new ArrayList<>();
        if (queueNames.isEmpty())
        {
            return consumers;
        }
        ManagementChannel management = brokerSession.requireManagement();
        for (int page = 1;; page++)
        {
            JsonNode data = pagedData(
                    management.invoke(ResourceNames.BROKER, "listConsumers", NO_FILTER, page, LIST_PAGE_SIZE));
            if (data == null || data.isEmpty())
            {
                break;
            }
            for (JsonNode node : data)
            {
                String queue = text(node, "queue");
                if (queue == null || !queueNames.contains(queue))
                {
                    continue;
                }
                consumers.add(new SubscriberConsumer(text(node, "id"), queue, orEmpty(text(node, "clientID")),
                        orEmpty(text(node, "user")), orEmpty(text(node, "remoteAddress")),
                        orEmpty(text(node, "protocol")), orEmpty(text(node, "filter")),
                        asLong(text(node, "messagesDelivered")), asLong(text(node, "messagesAcknowledged"))));
            }
            if (data.size() < LIST_PAGE_SIZE)
            {
                break;
            }
        }
        return consumers;
    }

    public List<BrokerProducer> producers()
    {
        String ourConnection = ourConnectionId();
        List<BrokerProducer> producers = new ArrayList<>();
        for (JsonNode node : array(invoke("listProducersInfoAsJSON")))
        {
            String connectionId = text(node, "connectionID");
            producers.add(new BrokerProducer(text(node, "id"), text(node, "destination"), connectionId,
                    epoch(node.get("creationTime")), asLong(text(node, "msgSent")), asLong(text(node, "msgSizeSent")),
                    // This tool's own management channel sends every request as a producer to
                    // activemq.management, so it shows up here just like its reply consumer shows
                    // up in consumers(). Label it rather than hide it, for the same reason.
                    connectionId != null && connectionId.equals(ourConnection)));
        }
        return producers;
    }

    /**
     * Which connection is ours, worked out by finding the connection our own management reply consumer sits on. There
     * is no "who am I" management call.
     */
    private String ourConnectionId()
    {
        String replyQueue = brokerSession.requireManagement().replyQueueName();
        for (JsonNode node : array(invoke("listAllConsumersAsJSON")))
        {
            if (replyQueue.equals(text(node, "queueName")))
            {
                return text(node, "connectionID");
            }
        }
        return null;
    }

    private Object invoke(String operation)
    {
        return brokerSession.requireManagement().invoke(ResourceNames.BROKER, operation);
    }

    private List<JsonNode> array(Object result)
    {
        if (result == null)
        {
            return List.of();
        }
        try
        {
            JsonNode root = objectMapper.readTree(result.toString());
            if (!root.isArray())
            {
                return List.of();
            }
            List<JsonNode> nodes = new ArrayList<>();
            root.forEach(nodes::add);
            return nodes;
        }
        catch (Exception e)
        {
            throw new BrokerException("Could not read the broker's response: " + e.getMessage(), e);
        }
    }

    /** The {@code server} object of the status attribute's JSON, or null when it has none. */
    private JsonNode statusServer(Object status)
    {
        try
        {
            return objectMapper.readTree(status.toString()).get("server");
        }
        catch (Exception e)
        {
            throw new BrokerException("the status attribute is not JSON: " + e.getMessage(), e);
        }
    }

    private Reading<String> fromStatus(Reading<JsonNode> server, String field)
    {
        if (!server.available())
        {
            return server.absent();
        }
        JsonNode value = server.value().get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? Reading.failed("the status attribute has no server." + field)
                : Reading.of(value.asText());
    }

    /** The {@code data} array of a paged {@code {"data":[...],"count":N}} reply. */
    private JsonNode pagedData(Object result)
    {
        if (result == null)
        {
            return null;
        }
        try
        {
            JsonNode data = objectMapper.readTree(result.toString()).get("data");
            return data == null || !data.isArray() ? null : data;
        }
        catch (Exception e)
        {
            throw new BrokerException("Could not read the broker's response: " + e.getMessage(), e);
        }
    }

    private String orEmpty(String value)
    {
        return value == null ? "" : value;
    }

    private String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String epoch(JsonNode value)
    {
        if (value == null || value.isNull())
        {
            return "";
        }
        long millis = value.asLong();
        return millis == 0 ? "" : TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(millis));
    }

    private long asLong(String value)
    {
        if (value == null || value.isBlank())
        {
            return 0L;
        }
        try
        {
            return Long.parseLong(value.trim());
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

}
