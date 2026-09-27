package com.culberth.tools.artemisbrowser.broker;

/**
 * One consumer currently attached to a queue.
 *
 * @param browseOnly   true for browsers, which read without consuming — this tool's own reads show up here, and so does
 *                     anyone else's browser
 * @param self         true when this consumer belongs to this tool's own management channel
 * @param consumerId   the consumer's number within its session — not unique on its own
 * @param sequentialId unique on the broker, and what {@code listConsumers} calls {@code id}
 */
public record BrokerConsumer(String consumerId, String queueName, String connectionId, String sessionId,
        String sequentialId, boolean browseOnly, long deliveringCount, long messagesDelivered,
        long messagesAcknowledged, String status, boolean self)
{

    /**
     * {@code <connectionID>:<sessionID>:<consumerID>}, the id the broker gives this consumer in
     * {@code listDeliveringMessagesAsJSON} — the only way to tie those messages to a consumer.
     */
    public String key()
    {
        return connectionId + ":" + sessionId + ":" + consumerId;
    }
}
