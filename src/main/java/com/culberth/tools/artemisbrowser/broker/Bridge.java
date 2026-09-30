package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * A core bridge: consumes one local queue and sends what it takes to another broker.
 *
 * <p>
 * Its user and password are not management attributes at all, so they are never read. What is read is where it takes
 * from, where it sends, and the broker's own {@code started} and {@code connected} flags. Connected means this broker
 * holds a connection to the target; it does not show the target accepted, routed or kept anything.
 *
 * <p>
 * The counters mean less than their names. On 2.55.0 and 2.57.0, ten messages bridged and acknowledged read
 * {@code messagesAcknowledged=10} and {@code messagesPendingAcknowledgement=10} with the source queue empty: "pending"
 * is a running total of what was sent and never goes down. So {@link #sent} holds it under its honest name and
 * {@link #outstanding()} is the difference. A bridge that cannot reach its target sends nothing — its backlog is simply
 * the source queue's depth.
 *
 * @param forwardingAddress where on the target it sends, or empty when each message keeps its own address
 * @param connectors        the static connectors it tries, by name; empty when it uses discovery
 */
public record Bridge(String name, String queueName, String forwardingAddress, String filter, List<String> connectors,
        String discoveryGroup, boolean started, boolean connected, boolean ha, long acknowledged, long sent,
        String transformerClassName)
{

    /** Sent and not yet acknowledged, derived from two cumulative counters. */
    public long outstanding()
    {
        return Math.max(0, sent - acknowledged);
    }

    public boolean filtered()
    {
        return filter != null && !filter.isBlank();
    }

    public boolean forwardsElsewhere()
    {
        return forwardingAddress != null && !forwardingAddress.isBlank();
    }

    public boolean transformed()
    {
        return transformerClassName != null && !transformerClassName.isBlank();
    }

    public boolean discovery()
    {
        return discoveryGroup != null && !discoveryGroup.isBlank();
    }
}
