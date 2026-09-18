package com.culberth.tools.artemisbrowser.broker;

import java.util.Map;

/**
 * One message in full: the entire body, every JMS header, every property.
 *
 * <p>
 * Read through a JMS {@code QueueBrowser} with a {@code JMSMessageID} selector rather than through management
 * {@code browse}, because management only surfaces {@code text} bodies — a bytes or map message would otherwise show
 * nothing here.
 */
public record MessageDetail(String queueName, String messageId, String correlationId, String type, String destination,
        String timestampText, String expirationText, int priority, boolean persistent, boolean redelivered,
        long deliveryCount, String groupId, String body, boolean bodyTruncated, Map<String, String> properties)
{
}
