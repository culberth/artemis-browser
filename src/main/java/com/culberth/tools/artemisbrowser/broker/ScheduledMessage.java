package com.culberth.tools.artemisbrowser.broker;

import java.util.Map;

/**
 * A message the broker is holding back until its delivery time.
 *
 * <p>
 * Separate from {@link MessageSummary} because it comes from somewhere else and carries less. Management {@code browse}
 * does not return scheduled messages at all — a queue holding one reports a message count of 1 and browses as empty —
 * so they are read through {@code listScheduledMessagesAsJSON}, which has no body field. What it does have, and the
 * reason this exists, is the delivery time.
 *
 * @param scheduledFor when the broker will make it available; the one thing that cannot be seen anywhere else
 * @param overdue      true when that time has already passed, which means something is wrong rather than pending
 */
public record ScheduledMessage(String messageId, long coreId, String type, int priority, boolean durable,
        String timestampText, String scheduledForText, boolean overdue, Map<String, String> properties)
{
}
