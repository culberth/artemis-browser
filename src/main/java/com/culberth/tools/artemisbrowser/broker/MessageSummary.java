package com.culberth.tools.artemisbrowser.broker;

import java.util.Map;

/** One browsed message, flattened for display. Never includes a deserialized object body. */
public record MessageSummary(int position, String messageId, String correlationId, String type, Long timestamp,
        String timestampText, int priority, boolean persistent, boolean redelivered, Long expiration, String groupId,
        String bodyPreview, boolean bodyTruncated, Map<String, String> properties)
{
}
