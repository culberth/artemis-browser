package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * One consumer's share of a queue's in-flight messages.
 *
 * <p>
 * The broker names the consumer only by a {@code ServerConsumer} {@code toString()}, whose {@code id} is
 * {@code <connectionID>:<sessionID>:<consumerID>} — the same three values {@code listAllConsumersAsJSON} reports, which
 * is how a consumer here is tied to a client. The session ID is not colon-free: OpenWire's looks like
 * {@code ID:host-63679-1790546223121-1:1:1}. So the connection ID is taken up to the first colon and the consumer ID
 * after the last, never by splitting. When the text does not have that shape at all, the ids are null and
 * {@code consumerName} is shown as it came rather than guessed at.
 *
 * @param consumerName the broker's text for this consumer, verbatim; empty when the consumer is known only from the
 *                     consumer listing, because the messages were not read
 * @param connectionId null when {@code consumerName} could not be parsed
 * @param client       who the consumer is, from {@code listConsumers}; null when it could not be matched
 * @param inTransit    how many messages the broker says this consumer holds, which is the whole number even when
 *                     {@code messages} is cut short or was not read; null when it could not be matched
 */
public record InFlightConsumer(String consumerName, String connectionId, String sessionId, String consumerId,
        List<InFlightMessage> messages, SubscriberConsumer client, Long inTransit)
{

    /** Rows shown per consumer. The rest are counted but not drawn; a page of 5,000 rows helps nobody. */
    public static final int SHOWN = 200;

    public InFlightConsumer(String consumerName, String connectionId, String sessionId, String consumerId,
            List<InFlightMessage> messages)
    {
        this(consumerName, connectionId, sessionId, consumerId, messages, null, null);
    }

    /** Whether the consumer's text parsed, so it can be matched against the consumer listings. */
    public boolean identified()
    {
        return connectionId != null;
    }

    /** The key {@code listAllConsumersAsJSON} can be matched by, or null when the text did not parse. */
    public String key()
    {
        return identified() ? connectionId + ":" + sessionId + ":" + consumerId : null;
    }

    public InFlightConsumer withClient(SubscriberConsumer client, Long inTransit)
    {
        return new InFlightConsumer(consumerName, connectionId, sessionId, consumerId, messages, client, inTransit);
    }

    /** How many this consumer holds: the broker's count where there is one, else what was listed. */
    public long holding()
    {
        return inTransit != null ? inTransit : messages.size();
    }

    public List<InFlightMessage> shown()
    {
        return messages.size() <= SHOWN ? messages : messages.subList(0, SHOWN);
    }

    /** Held by this consumer and not drawn — cut here for display, at the limit, or never read. */
    public long notShown()
    {
        return Math.max(0, holding() - shown().size());
    }
}
