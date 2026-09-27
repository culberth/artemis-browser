package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * Everything about one address: its counters, who is subscribed and how, who is attached to each subscription, and who
 * is sending to it.
 */
public record AddressDetail(AddressOverview address, List<Subscription> subscriptions,
        List<SubscriberConsumer> consumers, List<BrokerProducer> producers)
{

    public List<SubscriberConsumer> consumersOf(Subscription subscription)
    {
        return consumers.stream().filter(consumer -> subscription.name().equals(consumer.queueName())).toList();
    }

    /**
     * True when an attached consumer's client id matches the one split from the queue's name — which turns the guess
     * into something the broker said.
     */
    public boolean identityConfirmed(Subscription subscription)
    {
        return subscription.clientIdHint() != null && consumersOf(subscription).stream()
                .anyMatch(consumer -> subscription.clientIdHint().equals(consumer.clientId()));
    }
}
