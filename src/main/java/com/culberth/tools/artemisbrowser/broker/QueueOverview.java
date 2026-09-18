package com.culberth.tools.artemisbrowser.broker;

/**
 * One row of the all-queues overview, as reported by {@code broker.listQueues}.
 *
 * <p>
 * Separate from {@link QueueStats} because it comes from a different call with a different cost profile: one management
 * round trip returns every queue, where {@code QueueStats} is a focused read of the queue the user selected.
 */
public record QueueOverview(String name, String address, String routingType, long messageCount, long deliveringCount,
        long scheduledCount, int consumerCount, long messagesAdded, long messagesAcked, boolean durable, boolean paused,
        boolean internalQueue)
{

    /** True when anything is sitting on the queue with nothing attached to take it. */
    public boolean stalled()
    {
        return messageCount > 0 && consumerCount == 0;
    }
}
