package com.culberth.tools.artemisbrowser.broker;

/** One client connection currently attached to the broker. */
public record BrokerConnection(String connectionId, String clientAddress, String createdText, int sessionCount,
        boolean self)
{
}
