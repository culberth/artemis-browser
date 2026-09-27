package com.culberth.tools.artemisbrowser.broker;

import java.util.List;
import java.util.Map;

/**
 * Everything about one address: its counters, who is subscribed and how, who is attached to each subscription, who is
 * sending to it, and how far behind each subscriber is.
 *
 * @param oldestUndeliveredMillis per queue name, the age of the oldest message not yet handed to a consumer; absent
 *                                when there is none or it could not be read
 */
public record AddressDetail(AddressOverview address, List<Subscription> subscriptions,
        List<SubscriberConsumer> consumers, List<BrokerProducer> producers, Map<String, Long> oldestUndeliveredMillis)
{

    public AddressDetail(AddressOverview address, List<Subscription> subscriptions, List<SubscriberConsumer> consumers,
            List<BrokerProducer> producers)
    {
        this(address, subscriptions, consumers, producers, Map.of());
    }

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

    public Long oldestUndelivered(Subscription subscription)
    {
        return oldestUndeliveredMillis.get(subscription.name());
    }

    /**
     * The subscription whose oldest undelivered message has waited longest — the one furthest behind — or null when
     * fewer than two have anything waiting, since "furthest behind" of one says nothing.
     *
     * <p>
     * Deliberately by age and not by comparing {@code messagesAdded}: a filtered subscription is meant to receive fewer
     * messages, so a lower count is its filter working, not lag. Age of what is waiting means the same thing on every
     * subscription whatever its filter.
     */
    public String furthestBehind()
    {
        if (oldestUndeliveredMillis.size() < 2)
        {
            return null;
        }
        return oldestUndeliveredMillis.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(null);
    }

    public String oldestUndeliveredText(Subscription subscription)
    {
        Long millis = oldestUndelivered(subscription);
        return millis == null ? "" : ageText(millis);
    }

    /** "45s", "12m 3s", "4h 10m", "3d 2h" — two units at most; anything finer is noise at this scale. */
    static String ageText(long millis)
    {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60)
        {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60)
        {
            return minutes + "m " + seconds % 60 + "s";
        }
        long hours = minutes / 60;
        if (hours < 24)
        {
            return hours + "h " + minutes % 60 + "m";
        }
        return hours / 24 + "d " + hours % 24 + "h";
    }
}
