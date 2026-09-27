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
 * @param consumerName the broker's text for this consumer, verbatim
 * @param connectionId null when {@code consumerName} could not be parsed
 */
public record InFlightConsumer(String consumerName, String connectionId, String sessionId, String consumerId,
        List<InFlightMessage> messages)
{

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
}
