package com.culberth.tools.artemisbrowser.broker;

import java.util.Map;

/**
 * A message delivered to a consumer and not yet acknowledged, as {@code listDeliveringMessagesAsJSON} describes it.
 *
 * <p>
 * Headers and properties only. The broker returns no body for these, and the JMS browser cannot see them either, so an
 * in-flight message can be listed but not opened. Nor is there a delivery time: {@code timestamp} is when it was sent,
 * so an age measured from it says how old the message is, not how long a consumer has held it.
 *
 * @param messageId the JMS message ID ({@code userID}), which is what the rest of the tool links by
 * @param coreId    the broker's own numeric message ID
 * @param timestamp send time in epoch millis, 0 when absent
 * @param ageText   how long ago it was <em>sent</em>, when read; empty without a timestamp
 */
public record InFlightMessage(String messageId, long coreId, String type, int priority, boolean durable, long timestamp,
        String timestampText, String ageText, Map<String, String> properties)
{
}
