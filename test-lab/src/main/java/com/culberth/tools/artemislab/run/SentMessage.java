package com.culberth.tools.artemislab.run;

/**
 * One message the broker accepted, as the send manifest records it: enough to find it in Artemis Browser and compare
 * what Browser shows or exports against what was sent. Bodies are deterministic from the recipe, so they are described,
 * not stored.
 *
 * @param queue     the owned queue it was sent to
 * @param seq       its {@code labSeq} property, 1-based within the queue
 * @param kind      the body kind, e.g. {@code text}, {@code bytes}, {@code map}
 * @param messageId the JMS message id the client assigned — what Browser shows and looks up as the user id
 * @param bodyBytes body size as sent (characters for text)
 * @param note      what it is for: a marker, a schedule, a case it serves
 */
public record SentMessage(String queue, int seq, String kind, String messageId, int bodyBytes, String note)
{
}
