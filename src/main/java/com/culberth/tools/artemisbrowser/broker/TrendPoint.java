package com.culberth.tools.artemisbrowser.broker;

/**
 * One queue's counters at one reading this session kept for its trend.
 *
 * @param epoch   which run of the broker the reading belongs to; it goes up when the broker restarted in between, so
 *                readings either side of a restart are never joined
 * @param queueId the broker's id for the queue, -1 when unknown; a different id under the same name is a queue deleted
 *                and created again
 */
public record TrendPoint(long takenAt, int epoch, long queueId, long depth, long added, long acked, long expired,
        long killed, int consumers)
{

    static TrendPoint of(long takenAt, int epoch, QueueOverview queue)
    {
        return new TrendPoint(takenAt, epoch, queue.id(), queue.messageCount(), queue.messagesAdded(),
                queue.messagesAcked(), queue.messagesExpired(), queue.messagesKilled(), queue.consumerCount());
    }
}
