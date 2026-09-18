package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Service;

/** Reads the broker's queue list and per-queue counters through the management address. */
@Service
public class QueueDirectory {

    private final BrokerSession brokerSession;

    public QueueDirectory(BrokerSession brokerSession) {
        this.brokerSession = brokerSession;
    }

    /**
     * Every queue the broker knows about, sorted case-insensitively.
     *
     * <p>The only exclusion is this session's own management reply queue, which is a temporary
     * queue this tool created and would otherwise list as browsable under a UUID name.
     *
     * <p>Artemis's internal queues (names beginning {@code $}) are deliberately NOT hidden: a tool
     * whose job is showing what is on the broker should not decide some of it does not count.
     */
    public List<String> queueNames() {
        ManagementChannel management = brokerSession.requireManagement();
        Object result = management.invoke(ResourceNames.BROKER, "getQueueNames");
        List<String> names = new ArrayList<>();
        if (result instanceof Object[] array) {
            for (Object entry : array) {
                if (entry != null && !entry.toString().equals(management.replyQueueName())) {
                    names.add(entry.toString());
                }
            }
        } else if (result != null) {
            names.add(result.toString());
        }
        names.sort(Comparator.comparing(String::toLowerCase));
        return names;
    }

    /** A point-in-time read of one queue's counters. Reads attributes only; moves nothing. */
    public QueueStats stats(String queueName) {
        ManagementChannel management = brokerSession.requireManagement();
        String resource = ResourceNames.QUEUE + queueName;
        return new QueueStats(
                queueName,
                string(management.attribute(resource, "address"), queueName),
                string(management.attribute(resource, "routingType"), "ANYCAST"),
                number(management.attribute(resource, "messageCount")),
                number(management.attribute(resource, "deliveringCount")),
                number(management.attribute(resource, "scheduledCount")),
                (int) number(management.attribute(resource, "consumerCount")),
                number(management.attribute(resource, "messagesAdded")),
                number(management.attribute(resource, "messagesAcknowledged")),
                bool(management.attribute(resource, "durable")),
                bool(management.attribute(resource, "paused")));
    }

    private long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private boolean bool(Object value) {
        return value instanceof Boolean b && b;
    }

    private String string(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }
}
