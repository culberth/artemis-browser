package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Everything the broker knows about one client — the page that starts from "what is {@code billing-svc} doing?" rather
 * than from a queue or an address.
 *
 * <p>
 * Built from the paged listings, verified on 2.44.0, because only they carry a client id: {@code listConnectionsAsJSON}
 * has neither client id nor protocol. The chain is client id or connection id → {@code listConnections} → the
 * connections' sessions from {@code listSessions}, the one listing that ties a session to its connection → the
 * consumers ({@code listConsumers}) and producers ({@code listProducers}) on those sessions. Neither of those last two
 * carries a connection id; both carry the session. Four listings, filtered here rather than by the broker, as the rest
 * of the tool does: a broker-side filter that silently matched nothing would read as "this client does nothing".
 */
@Service
public class ClientDirectory
{

    private static final String NO_FILTER = "{\"field\":\"\",\"operation\":\"\",\"value\":\"\"}";
    private static final int LIST_PAGE_SIZE = 200;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final BrokerSession brokerSession;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClientDirectory(BrokerSession brokerSession)
    {
        this.brokerSession = brokerSession;
    }

    /**
     * The client with this client id, or else the one connection with this connection id; null when the broker has no
     * such client connected.
     */
    public ClientView find(String clientId, String connectionId)
    {
        boolean byClientId = clientId != null && !clientId.isBlank();
        if (!byClientId && (connectionId == null || connectionId.isBlank()))
        {
            return null;
        }
        List<ClientView.Connection> connections = new ArrayList<>();
        for (JsonNode node : all("listConnections"))
        {
            String id = text(node, "connectionID");
            boolean match = byClientId ? clientId.equals(text(node, "clientID")) : connectionId.equals(id);
            if (match)
            {
                connections.add(new ClientView.Connection(id, text(node, "remoteAddress"), text(node, "users"),
                        text(node, "protocol"), text(node, "creationTime"), number(node, "sessionCount")));
            }
        }
        if (connections.isEmpty())
        {
            return null;
        }

        Set<String> connectionIds = connections.stream().map(ClientView.Connection::connectionId)
                .collect(Collectors.toSet());
        List<ClientView.Session> sessions = select(all("listSessions"),
                node -> connectionIds.contains(text(node, "connectionID")) ? new ClientView.Session(text(node, "id"),
                        text(node, "connectionID"), number(node, "consumerCount"), number(node, "producerCount"),
                        text(node, "creationTime")) : null);

        Set<String> sessionIds = sessions.stream().map(ClientView.Session::sessionId).collect(Collectors.toSet());
        List<ClientView.Consumer> consumers = select(all("listConsumers"),
                node -> sessionIds.contains(text(node, "session"))
                        ? new ClientView.Consumer(text(node, "id"), text(node, "session"), text(node, "queue"),
                                text(node, "address"), text(node, "filter"), number(node, "messagesDelivered"),
                                number(node, "messagesAcknowledged"), number(node, "messagesInTransit"))
                        : null);
        List<ClientView.Producer> producers = select(all("listProducers"),
                node -> sessionIds.contains(text(node, "session"))
                        ? new ClientView.Producer(text(node, "id"), text(node, "session"), text(node, "address"),
                                number(node, "msgSent"), number(node, "msgSizeSent"), epoch(node, "creationTime"))
                        : null);

        return new ClientView(byClientId ? clientId : "", byClientId ? null : connectionId, connections, sessions,
                consumers, producers);
    }

    private <T> List<T> select(List<JsonNode> nodes, Function<JsonNode, T> ifMatching)
    {
        List<T> selected = new ArrayList<>();
        for (JsonNode node : nodes)
        {
            T value = ifMatching.apply(node);
            if (value != null)
            {
                selected.add(value);
            }
        }
        return selected;
    }

    /** Every row of a paged broker listing. */
    private List<JsonNode> all(String operation)
    {
        ManagementChannel management = brokerSession.requireManagement();
        List<JsonNode> rows = new ArrayList<>();
        for (int page = 1;; page++)
        {
            Object result = management.invoke(ResourceNames.BROKER, operation, NO_FILTER, page, LIST_PAGE_SIZE);
            JsonNode data = data(operation, result);
            if (data == null || data.isEmpty())
            {
                break;
            }
            data.forEach(rows::add);
            if (data.size() < LIST_PAGE_SIZE)
            {
                break;
            }
        }
        return rows;
    }

    private JsonNode data(String operation, Object result)
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
            throw new BrokerException("Could not read the broker's " + operation + " reply: " + e.getMessage(), e);
        }
    }

    private String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }

    /** Counters are bare in these listings, but read either way — the queue listing taught that lesson. */
    private long number(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || value.isNull())
        {
            return 0;
        }
        if (value.isNumber())
        {
            return value.asLong();
        }
        try
        {
            return Long.parseLong(value.asString().trim());
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    /** {@code listProducers} writes its creation time as quoted epoch millis, unlike the others' date strings. */
    private String epoch(JsonNode node, String field)
    {
        long millis = number(node, field);
        return millis <= 0 ? "" : TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(millis));
    }
}
