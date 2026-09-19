package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

    private final BrokerSession brokerSession;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BrokerInfoService(BrokerSession brokerSession)
    {
        this.brokerSession = brokerSession;
    }

    public BrokerHealth health()
    {
        ManagementChannel management = brokerSession.requireManagement();
        String statusJson = string(management.attribute(ResourceNames.BROKER, "status"));

        return new BrokerHealth(string(management.attribute(ResourceNames.BROKER, "version")),
                string(management.attribute(ResourceNames.BROKER, "uptime")),
                fromStatus(statusJson, "state", "UNKNOWN"), fromStatus(statusJson, "nodeId", ""),
                number(management.attribute(ResourceNames.BROKER, "connectionCount")),
                number(management.attribute(ResourceNames.BROKER, "sessionCount")),
                number(management.attribute(ResourceNames.BROKER, "totalConsumerCount")),
                number(management.attribute(ResourceNames.BROKER, "addressMemoryUsage")),
                number(management.attribute(ResourceNames.BROKER, "addressMemoryUsagePercentage")),
                // diskStoreUsage is a 0..1 ratio; maxDiskUsage and diskPressure() work in 0..100.
                decimal(management.attribute(ResourceNames.BROKER, "diskStoreUsage")) * 100,
                number(management.attribute(ResourceNames.BROKER, "maxDiskUsage")));
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
                    Boolean.parseBoolean(text(node, "browseOnly")), asLong(text(node, "deliveringCount")),
                    asLong(text(node, "messagesDelivered")), asLong(text(node, "messagesAcknowledged")),
                    text(node, "status"),
                    // This tool's own management reply consumer. Worth labelling rather than
                    // hiding: someone counting consumers on a quiet broker should see why it is 1.
                    queueName != null && queueName.equals(replyQueue)));
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

    private String fromStatus(String statusJson, String field, String fallback)
    {
        if (statusJson == null || statusJson.isBlank())
        {
            return fallback;
        }
        try
        {
            JsonNode server = objectMapper.readTree(statusJson).get("server");
            if (server == null)
            {
                return fallback;
            }
            JsonNode value = server.get(field);
            return value == null || value.isNull() ? fallback : value.asText();
        }
        catch (Exception e)
        {
            return fallback;
        }
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

    private String string(Object value)
    {
        return value == null ? "" : value.toString();
    }

    private long number(Object value)
    {
        return value instanceof Number n ? n.longValue() : asLong(string(value));
    }

    private double decimal(Object value)
    {
        if (value instanceof Number n)
        {
            return n.doubleValue();
        }
        try
        {
            return Double.parseDouble(string(value));
        }
        catch (NumberFormatException e)
        {
            return 0d;
        }
    }
}
