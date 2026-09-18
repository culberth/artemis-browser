package com.culberth.tools.artemisbrowser.broker;

/**
 * One row in the message list, built from Artemis's management {@code browse}.
 *
 * <p>
 * Deliberately shallow: the list shows what fits in a table, and the per-message detail view fetches the full body and
 * properties separately. Putting every property of every message into the list page is what makes a browser unusable on
 * a queue with 50,000 messages on it.
 *
 * @param position  1-based index across the whole filtered result, not just this page
 * @param messageId the JMS message ID ({@code userID} in management terms) — this is the handle the detail view looks
 *                  the message up by
 */
public record MessageSummary(long position, String messageId, String coreId, String type, Long timestamp,
        String timestampText, int priority, boolean persistent, boolean redelivered, long sizeBytes, String protocol,
        String bodyPreview, boolean bodyTruncated)
{
}
