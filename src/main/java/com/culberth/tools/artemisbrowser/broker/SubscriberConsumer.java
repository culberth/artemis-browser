package com.culberth.tools.artemisbrowser.broker;

/**
 * One consumer attached to a queue, with who it is — read from {@code broker.listConsumers}, which is the only consumer
 * listing that carries the client id. {@code listAllConsumersAsJSON}, which {@link BrokerConsumer} comes from, has
 * none.
 *
 * @param clientId empty when the client set none
 * @param filter   the consumer's own selector, separate from the queue's filter; empty when it has none
 */
public record SubscriberConsumer(String consumerId, String queueName, String clientId, String user,
        String remoteAddress, String protocol, String filter, long messagesDelivered, long messagesAcknowledged)
{
}
