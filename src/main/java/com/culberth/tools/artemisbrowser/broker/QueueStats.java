package com.culberth.tools.artemisbrowser.broker;

/**
 * A point-in-time read of a queue's management attributes — "what is going on in the queue"
 * without touching its messages.
 *
 * @param messageCount        messages currently on the queue, delivering ones included
 * @param deliveringCount     messages sent to a consumer but not yet acknowledged
 * @param scheduledCount      messages held back until their scheduled delivery time
 * @param consumerCount       consumers currently attached
 * @param messagesAdded       messages routed to this queue since the broker started
 * @param messagesAcknowledged messages acknowledged since the broker started
 * @param address             the address this queue is bound to; differs from {@code name} for
 *                            multicast subscriptions, which is what forces FQQN addressing
 * @param routingType         ANYCAST or MULTICAST
 */
public record QueueStats(
        String name,
        String address,
        String routingType,
        long messageCount,
        long deliveringCount,
        long scheduledCount,
        int consumerCount,
        long messagesAdded,
        long messagesAcknowledged,
        boolean durable,
        boolean paused) {

    /**
     * How this queue must be named when opening a browser on it. Artemis resolves a bare name
     * against addresses first, so a queue whose name differs from its address (any multicast
     * subscription) can only be reached by its fully-qualified {@code address::queue} name.
     */
    public String browseName() {
        return address == null || address.equals(name) ? name : address + "::" + name;
    }
}
