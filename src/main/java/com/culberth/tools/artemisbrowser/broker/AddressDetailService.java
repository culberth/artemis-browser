package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Assembles one address: its counters, its subscriptions, the consumers on each, the producers sending to it, and how
 * far behind each subscription is.
 *
 * <p>
 * Four listings — the address listing (through {@link AddressDirectory}), a {@code listQueues} filtered to the address,
 * {@code listConsumers}, {@code listProducersInfoAsJSON} — plus one {@code firstMessageAge} read per subscription that
 * has anything on it, the address's settings, and the broker's diverts. The per-queue and per-divert reads are why this
 * is a page for one address and not part of the index.
 */
@Service
public class AddressDetailService
{

    /** How many matches to fetch per subscription when looking for a message; enough to show, not to page. */
    static final int FIND_LIMIT = 20;

    private final AddressDirectory addressDirectory;
    private final QueueDirectory queueDirectory;
    private final BrokerInfoService brokerInfo;
    private final QueueBrowseService browseService;
    private final DivertDirectory divertDirectory;

    public AddressDetailService(AddressDirectory addressDirectory, QueueDirectory queueDirectory,
            BrokerInfoService brokerInfo, QueueBrowseService browseService, DivertDirectory divertDirectory)
    {
        this.addressDirectory = addressDirectory;
        this.queueDirectory = queueDirectory;
        this.brokerInfo = brokerInfo;
        this.browseService = browseService;
        this.divertDirectory = divertDirectory;
    }

    /** The address by exact name, or null when the broker has no such address. */
    public AddressDetail detail(String name)
    {
        List<AddressOverview> all = addressDirectory.overview();
        AddressOverview address = all.stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElse(null);
        if (address == null)
        {
            return null;
        }
        List<Subscription> subscriptions = queueDirectory.onAddress(name);
        Set<String> queueNames = subscriptions.stream().map(Subscription::name).collect(Collectors.toSet());
        List<SubscriberConsumer> consumers = brokerInfo.consumersOn(queueNames);
        // Our own management producer sends to activemq.management; on that address it is labelled
        // on /broker already, and here it would only be noise.
        List<BrokerProducer> producers = brokerInfo.producers().stream()
                .filter(producer -> name.equals(producer.address()) && !producer.self()).toList();

        Map<String, Long> oldest = new LinkedHashMap<>();
        for (Subscription subscription : subscriptions)
        {
            // An empty queue has no oldest message; skipping it saves the round trip, not a result.
            if (subscription.messageCount() == 0)
            {
                continue;
            }
            Long age = queueDirectory.oldestUndeliveredAgeMillis(subscription.name());
            if (age != null)
            {
                oldest.put(subscription.name(), age);
            }
        }
        return new AddressDetail(address, subscriptions, consumers, producers, oldest, routing(name, all));
    }

    private AddressRouting routing(String name, List<AddressOverview> all)
    {
        AddressSettings settings = null;
        String settingsError = null;
        try
        {
            settings = addressDirectory.settings(name);
        }
        catch (BrokerException e)
        {
            // Settings explain the page; they are not the page. A broker that refuses this one read
            // should still show who is subscribed.
            settingsError = e.getMessage();
        }
        List<Divert> diverts = divertDirectory.all();
        return new AddressRouting(settings, settingsError, DivertDirectory.from(diverts, name),
                DivertDirectory.to(diverts, name), all.stream().map(AddressOverview::name).collect(Collectors.toSet()));
    }

    /**
     * Looks for messages matching {@code filter} in each of the address's subscriptions, reporting every subscription
     * whether or not anything was found.
     *
     * @param filter Artemis core filter syntax
     */
    public SubscriptionSearch find(AddressDetail detail, String filter)
    {
        String effectiveFilter = filter == null ? "" : filter.trim();
        if (effectiveFilter.isEmpty())
        {
            throw new BrokerException("Enter a filter to look for.");
        }
        List<SubscriptionSearch.Row> rows = new ArrayList<>();
        for (Subscription subscription : detail.subscriptions())
        {
            // The bare name: browse here is a management operation, and management resources are
            // named by the queue alone. The FQQN is for the JMS path only.
            List<MessageSummary> found = browseService.matching(subscription.name(), effectiveFilter, FIND_LIMIT);
            rows.add(new SubscriptionSearch.Row(subscription, found, found.size() >= FIND_LIMIT));
        }
        return new SubscriptionSearch(effectiveFilter, rows);
    }
}
