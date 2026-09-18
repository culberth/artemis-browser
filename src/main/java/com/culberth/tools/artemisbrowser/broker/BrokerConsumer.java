package com.culberth.tools.artemisbrowser.broker;

/**
 * One consumer currently attached to a queue.
 *
 * @param browseOnly true for browsers, which read without consuming — this tool's own reads show up here, and so does
 *                   anyone else's browser
 * @param self       true when this consumer belongs to this tool's own management channel
 */
public record BrokerConsumer(String consumerId, String queueName, String connectionId, boolean browseOnly,
        long deliveringCount, long messagesDelivered, long messagesAcknowledged, String status, boolean self)
{
}
