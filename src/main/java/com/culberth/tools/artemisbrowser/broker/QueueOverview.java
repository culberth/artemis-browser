package com.culberth.tools.artemisbrowser.broker;

/**
 * One row of the all-queues overview, as reported by {@code broker.listQueues}.
 *
 * <p>
 * Separate from {@link QueueStats} because it comes from a different call with a different cost profile: one management
 * round trip returns every queue, where {@code QueueStats} is a focused read of the queue the user selected.
 *
 * @param messagesExpired messages that expired on this queue since the broker started, whether they then went to an
 *                        expiry address or, with none, were dropped — the counter does not say which
 * @param messagesKilled  messages removed for exceeding max delivery attempts, dead-lettered or dropped alike — and, on
 *                        a queue that purges when its last consumer leaves, the purged ones too
 * @param behavior        its configuration, from the same listing row
 * @param id              the broker's id for this queue, or -1 when the row had none. Verified on 2.55.0: a queue
 *                        deleted and created again under the same name gets a new id, and a durable queue keeps its id
 *                        across a restart — so a changed id is a different queue, whatever its name.
 */
public record QueueOverview(String name, String address, String routingType, long messageCount, long deliveringCount,
        long scheduledCount, int consumerCount, long messagesAdded, long messagesAcked, boolean durable, boolean paused,
        boolean internalQueue, long messagesExpired, long messagesKilled, QueueBehavior behavior, long id)
{

    public QueueOverview(String name, String address, String routingType, long messageCount, long deliveringCount,
            long scheduledCount, int consumerCount, long messagesAdded, long messagesAcked, boolean durable,
            boolean paused, boolean internalQueue, long messagesExpired, long messagesKilled, QueueBehavior behavior)
    {
        this(name, address, routingType, messageCount, deliveringCount, scheduledCount, consumerCount, messagesAdded,
                messagesAcked, durable, paused, internalQueue, messagesExpired, messagesKilled, behavior, -1);
    }

    public QueueOverview(String name, String address, String routingType, long messageCount, long deliveringCount,
            long scheduledCount, int consumerCount, long messagesAdded, long messagesAcked, boolean durable,
            boolean paused, boolean internalQueue, long messagesExpired, long messagesKilled)
    {
        this(name, address, routingType, messageCount, deliveringCount, scheduledCount, consumerCount, messagesAdded,
                messagesAcked, durable, paused, internalQueue, messagesExpired, messagesKilled,
                QueueBehavior.NOT_COLLECTED);
    }

    /** The same row with its configuration — for tests. */
    public QueueOverview withBehavior(QueueBehavior behavior)
    {
        return new QueueOverview(name, address, routingType, messageCount, deliveringCount, scheduledCount,
                consumerCount, messagesAdded, messagesAcked, durable, paused, internalQueue, messagesExpired,
                messagesKilled, behavior, id);
    }

    /** The same row with its broker id — for tests. */
    public QueueOverview withId(long id)
    {
        return new QueueOverview(name, address, routingType, messageCount, deliveringCount, scheduledCount,
                consumerCount, messagesAdded, messagesAcked, durable, paused, internalQueue, messagesExpired,
                messagesKilled, behavior, id);
    }

    public QueueOverview(String name, String address, String routingType, long messageCount, long deliveringCount,
            long scheduledCount, int consumerCount, long messagesAdded, long messagesAcked, boolean durable,
            boolean paused, boolean internalQueue)
    {
        this(name, address, routingType, messageCount, deliveringCount, scheduledCount, consumerCount, messagesAdded,
                messagesAcked, durable, paused, internalQueue, 0, 0);
    }

    /** True when anything is sitting on the queue with nothing attached to take it. */
    public boolean stalled()
    {
        return messageCount > 0 && consumerCount == 0;
    }
}
