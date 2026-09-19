package com.culberth.tools.artemisbrowser.broker;

/** One producer currently attached to the broker, and the address it is sending to. */
public record BrokerProducer(String producerId, String address, String connectionId, String createdText,
        long messagesSent, long bytesSent, boolean self)
{
}
