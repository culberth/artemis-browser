package com.culberth.tools.artemisbrowser.broker;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Service;

/**
 * Reads the broker's queue list and counters through the management address.
 *
 * <p>
 * Everything here goes through {@code broker.listQueues}, which answers a JSON document holding every queue and every
 * counter in one round trip. The obvious alternative — reading {@code messageCount}, {@code consumerCount} and the rest
 * as individual management attributes — costs ten round trips per queue, which is fine for one queue and untenable for
 * an overview page that refreshes on a timer.
 */
@Service
public class QueueDirectory
{

    /** listQueues wants a filter document even when there is nothing to filter on. */
    private static final String NO_FILTER = "{\"field\":\"\",\"operation\":\"\",\"value\":\"\"}";

    /** Page size for the listQueues call itself; paged through until the broker stops adding rows. */
    private static final int LIST_PAGE_SIZE = 200;

    private final BrokerSession brokerSession;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueueDirectory(BrokerSession brokerSession)
    {
        this.brokerSession = brokerSession;
    }

    /**
     * Every queue the broker knows about, sorted case-insensitively.
     *
     * <p>
     * The only exclusion is this session's own management reply queue, which is a temporary queue this tool created and
     * would otherwise be listed as browsable under a UUID name.
     *
     * <p>
     * Artemis's internal queues are deliberately NOT hidden: a tool whose job is showing what is on the broker should
     * not decide some of it does not count. They are flagged instead.
     */
    public List<QueueOverview> overview()
    {
        ManagementChannel management = brokerSession.requireManagement();
        List<QueueOverview> queues = new ArrayList<>();

        for (int page = 1;; page++)
        {
            JsonNode data = listQueues(management, page);
            if (data == null || data.isEmpty())
            {
                break;
            }
            for (JsonNode node : data)
            {
                String name = text(node, "name");
                if (name == null || name.equals(management.replyQueueName()))
                {
                    continue;
                }
                queues.add(toOverview(node, name));
            }
            if (data.size() < LIST_PAGE_SIZE)
            {
                break;
            }
        }
        queues.sort(Comparator.comparing(queue -> queue.name().toLowerCase()));
        return queues;
    }

    /** Just the names, for the queue picker. */
    public List<String> queueNames()
    {
        return overview().stream().map(QueueOverview::name).toList();
    }

    /** Counters for one queue, or null when the broker no longer has it. */
    public QueueStats stats(String queueName)
    {
        for (QueueOverview queue : overview())
        {
            if (queue.name().equals(queueName))
            {
                return new QueueStats(queue.name(), queue.address(), queue.routingType(), queue.messageCount(),
                        queue.deliveringCount(), queue.scheduledCount(), queue.consumerCount(), queue.messagesAdded(),
                        queue.messagesAcked(), queue.durable(), queue.paused());
            }
        }
        return null;
    }

    private JsonNode listQueues(ManagementChannel management, int page)
    {
        Object result = management.invoke(ResourceNames.BROKER, "listQueues", NO_FILTER, page, LIST_PAGE_SIZE);
        if (result == null)
        {
            return null;
        }
        try
        {
            JsonNode root = objectMapper.readTree(result.toString());
            JsonNode data = root.get("data");
            return data == null || !data.isArray() ? null : data;
        }
        catch (Exception e)
        {
            throw new BrokerException("Could not read the broker's queue list: " + e.getMessage(), e);
        }
    }

    private QueueOverview toOverview(JsonNode node, String name)
    {
        return new QueueOverview(name, textOr(node, "address", name), textOr(node, "routingType", "ANYCAST"),
                number(node, "messageCount"), number(node, "deliveringCount"), number(node, "scheduledCount"),
                (int) number(node, "consumerCount"), number(node, "messagesAdded"), number(node, "messagesAcked"),
                flag(node, "durable"), flag(node, "paused"), flag(node, "internalQueue"));
    }

    private String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String textOr(JsonNode node, String field, String fallback)
    {
        String value = text(node, field);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** listQueues quotes every value, counters included: "messageCount":"12", not 12. */
    private long number(JsonNode node, String field)
    {
        String value = text(node, field);
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

    private boolean flag(JsonNode node, String field)
    {
        return Boolean.parseBoolean(text(node, field));
    }
}
