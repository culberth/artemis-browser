package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * One address and the queues bound to it.
 *
 * <p>
 * An address is the thing producers send to; queues are what consumers read from. For point-to-point traffic the two
 * line up one-to-one and the distinction is invisible, which is why a queue-only view gets you a long way. It stops
 * being invisible with multicast: one address fans out to a queue per subscriber, each holding its own copy, and none
 * of them named after the address.
 */
public record AddressOverview(String name, String routingTypes, long messageCount, long addressSizeBytes,
        long routedMessageCount, long unroutedMessageCount, boolean paging, boolean internal, boolean temporary,
        List<QueueOverview> queues)
{

    public boolean multicast()
    {
        return routingTypes != null && routingTypes.contains("MULTICAST");
    }

    /** Messages that reached the address but matched no queue — usually a misconfiguration. */
    public boolean hasUnrouted()
    {
        return unroutedMessageCount > 0;
    }
}
