package com.culberth.tools.artemisbrowser.broker;

import java.util.Map;

/**
 * One message in full: the entire body, every JMS header, every property.
 *
 * <p>
 * Read through a JMS {@code QueueBrowser} with a {@code JMSMessageID} selector rather than through management
 * {@code browse}, because management only surfaces {@code text} bodies — a bytes or map message would otherwise show
 * nothing here.
 *
 * @param propertyTypes each property's Java type by name ({@code String}, {@code Integer}…), so that a string
 *                      {@code "5"} and an integer {@code 5} — which a core filter treats as different — are not shown
 *                      as the same value
 */
public record MessageDetail(String queueName, String messageId, String correlationId, String type, String destination,
        String timestampText, String expirationText, int priority, boolean persistent, boolean redelivered,
        long deliveryCount, String groupId, boolean largeMessage, String body, boolean bodyTruncated,
        Map<String, String> properties, Map<String, String> propertyTypes)
{

    public MessageDetail(String queueName, String messageId, String correlationId, String type, String destination,
            String timestampText, String expirationText, int priority, boolean persistent, boolean redelivered,
            long deliveryCount, String groupId, boolean largeMessage, String body, boolean bodyTruncated,
            Map<String, String> properties)
    {
        this(queueName, messageId, correlationId, type, destination, timestampText, expirationText, priority,
                persistent, redelivered, deliveryCount, groupId, largeMessage, body, bodyTruncated, properties,
                Map.of());
    }
}
