package com.culberth.tools.artemisbrowser.broker;

import java.util.Map;

/**
 * One row in the message list, built from Artemis's management {@code browse}.
 *
 * <p>
 * Deliberately shallow: the list shows what fits in a table, and the per-message detail view fetches the full body
 * separately. Putting every <em>body</em> of every message into the list page is what makes a browser unusable on a
 * queue with 50,000 messages on it — the properties, by contrast, are what a filter is written against, and having to
 * open each message to find out what they are called is what makes a filter guesswork.
 *
 * @param position   1-based index across the whole filtered result, not just this page
 * @param messageId  the JMS message ID ({@code userID} in management terms) — this is the handle the detail view looks
 *                   the message up by
 * @param properties the message's own properties, Artemis's internal ones removed; the same names a core filter matches
 *                   on
 */
public record MessageSummary(long position, String messageId, String coreId, String type, Long timestamp,
        String timestampText, int priority, boolean persistent, boolean redelivered, long sizeBytes, String protocol,
        boolean largeMessage, Map<String, String> properties, String bodyPreview, boolean bodyTruncated)
{

    /**
     * The same row with a body read somewhere other than management browse — the JMS browser, for export, which is the
     * only route that sees a whole body or a non-text one.
     */
    public MessageSummary withBody(String body, boolean truncated)
    {
        return new MessageSummary(position, messageId, coreId, type, timestamp, timestampText, priority, persistent,
                redelivered, sizeBytes, protocol, largeMessage, properties, body, truncated);
    }
}
