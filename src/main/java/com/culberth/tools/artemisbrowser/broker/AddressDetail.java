package com.culberth.tools.artemisbrowser.broker;

import java.util.List;
import java.util.Map;

/**
 * Everything about one address: its counters, who is subscribed and how, who is attached to each subscription, who is
 * sending to it, how far behind each subscriber is, and where else its messages can go.
 *
 * @param oldestUndeliveredMillis per queue name, the age of the oldest message not yet handed to a consumer; absent
 *                                when there is none or it could not be read
 * @param agesNotRead             per queue name, why its age could not be read — so an unread age is not taken for a
 *                                queue with nothing waiting
 * @param pressure                its usage against its limits and policy
 */
public record AddressDetail(AddressOverview address, List<Subscription> subscriptions,
        List<SubscriberConsumer> consumers, List<BrokerProducer> producers, Map<String, Long> oldestUndeliveredMillis,
        AddressRouting routing, Map<String, String> agesNotRead, AddressPressure pressure)
{

    public AddressDetail(AddressOverview address, List<Subscription> subscriptions, List<SubscriberConsumer> consumers,
            List<BrokerProducer> producers, Map<String, Long> oldestUndeliveredMillis, AddressRouting routing,
            Map<String, String> agesNotRead)
    {
        this(address, subscriptions, consumers, producers, oldestUndeliveredMillis, routing, agesNotRead,
                new AddressPressure(address, Reading.notCollected("not asked for"),
                        Reading.notCollected("not asked for"), null));
    }

    public AddressDetail(AddressOverview address, List<Subscription> subscriptions, List<SubscriberConsumer> consumers,
            List<BrokerProducer> producers, Map<String, Long> oldestUndeliveredMillis, AddressRouting routing)
    {
        this(address, subscriptions, consumers, producers, oldestUndeliveredMillis, routing, Map.of());
    }

    public AddressDetail(AddressOverview address, List<Subscription> subscriptions, List<SubscriberConsumer> consumers,
            List<BrokerProducer> producers)
    {
        this(address, subscriptions, consumers, producers, Map.of(), AddressRouting.NONE);
    }

    public AddressDetail(AddressOverview address, List<Subscription> subscriptions, List<SubscriberConsumer> consumers,
            List<BrokerProducer> producers, Map<String, Long> oldestUndeliveredMillis)
    {
        this(address, subscriptions, consumers, producers, oldestUndeliveredMillis, AddressRouting.NONE);
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

    /** How much older the furthest-behind subscription's oldest message must be than the runner-up's. */
    static final long BEHIND_MIN_GAP_MILLIS = 60_000;

    /** And by what factor: twice as old, so two subscriptions an hour behind are not told apart by a minute. */
    static final long BEHIND_MIN_RATIO = 2;

    /**
     * The subscription clearly furthest behind, or null when none is.
     *
     * <p>
     * Deliberately by age and not by comparing {@code messagesAdded}: a filtered subscription is meant to receive fewer
     * messages, so a lower count is its filter working, not lag. Age of what is waiting means the same thing on every
     * subscription whatever its filter.
     *
     * <p>
     * "Clearly" is the point. Picking whichever age is largest put the badge on one of three subscriptions all showing
     * "4m 40s" — published in the same burst, a few milliseconds apart — which points at a subscriber that is not
     * behind anything. So the oldest must beat the runner-up by at least {@link #BEHIND_MIN_GAP_MILLIS} and be at least
     * {@link #BEHIND_MIN_RATIO} times its age. A subscription with nothing waiting counts as zero, so one subscription
     * an hour behind while the rest are caught up is marked.
     */
    public String furthestBehind()
    {
        if (subscriptions.size() < 2 || oldestUndeliveredMillis.isEmpty())
        {
            return null;
        }
        List<Map.Entry<String, Long>> byAge = oldestUndeliveredMillis.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList();
        long oldest = byAge.get(0).getValue();
        long runnerUp = byAge.size() > 1 ? byAge.get(1).getValue() : 0;
        boolean clearly = oldest - runnerUp >= BEHIND_MIN_GAP_MILLIS && oldest >= BEHIND_MIN_RATIO * runnerUp;
        return clearly ? byAge.get(0).getKey() : null;
    }

    /** Why this subscription's age is missing when it was asked for and not given; null otherwise. */
    public String ageNotRead(Subscription subscription)
    {
        return agesNotRead.get(subscription.name());
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
