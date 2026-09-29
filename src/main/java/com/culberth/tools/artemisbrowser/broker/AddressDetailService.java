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
    private final InFlightService inFlightService;

    public AddressDetailService(AddressDirectory addressDirectory, QueueDirectory queueDirectory,
            BrokerInfoService brokerInfo, QueueBrowseService browseService, DivertDirectory divertDirectory,
            InFlightService inFlightService)
    {
        this.addressDirectory = addressDirectory;
        this.queueDirectory = queueDirectory;
        this.brokerInfo = brokerInfo;
        this.browseService = browseService;
        this.divertDirectory = divertDirectory;
        this.inFlightService = inFlightService;
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
        Map<String, String> notRead = new LinkedHashMap<>();
        for (Subscription subscription : subscriptions)
        {
            // An empty queue has no oldest message; skipping it saves the round trip, not a result.
            if (subscription.messageCount() == 0)
            {
                continue;
            }
            // One subscription's age the broker will not give does not take the others with it.
            Reading<Long> age = Reading.attempt(() -> queueDirectory.oldestUndeliveredAgeMillis(subscription.name()));
            if (!age.available())
            {
                notRead.put(subscription.name(), age.explained());
            }
            else if (age.value() != null)
            {
                oldest.put(subscription.name(), age.value());
            }
        }
        return new AddressDetail(address, subscriptions, consumers, producers, oldest, routing(name, all), notRead);
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
        Reading<List<Divert>> diverts = Reading.attempt(divertDirectory::all);
        List<Divert> known = diverts.orElse(List.of());
        return new AddressRouting(settings, settingsError, DivertDirectory.from(known, name),
                DivertDirectory.to(known, name), all.stream().map(AddressOverview::name).collect(Collectors.toSet()),
                diverts.available() ? null : diverts.explained());
    }

    /**
     * Looks for messages matching {@code filter} in each of the address's subscriptions, reporting every subscription
     * whether or not anything was found.
     *
     * <p>
     * When the filter is an exact message ID ({@link MessageIdLookup}), a subscription browse did not find it on is
     * also checked for it in flight — browse first, so a message delivered in between is still caught by one of the
     * two. Each subscription holds its own copy, so each is checked whatever the others said.
     *
     * @param filter Artemis core filter syntax, or a bare {@code ID:…}
     */
    public SubscriptionSearch find(AddressDetail detail, String filter)
    {
        String effectiveFilter = MessageIdLookup.filterFor(filter);
        if (effectiveFilter.isEmpty())
        {
            throw new BrokerException("Enter a filter to look for.");
        }
        String messageId = MessageIdLookup.messageId(effectiveFilter);
        List<SubscriptionSearch.Row> rows = new ArrayList<>();
        for (Subscription subscription : detail.subscriptions())
        {
            // The bare name: browse here is a management operation, and management resources are
            // named by the queue alone. The FQQN is for the JMS path only.
            List<MessageSummary> found = browseService.matching(subscription.name(), effectiveFilter, FIND_LIMIT);
            InFlightLookup inFlight = messageId != null && found.isEmpty()
                    ? inFlightService.locate(subscription.name(), subscription.deliveringCount(), messageId)
                    : null;
            rows.add(new SubscriptionSearch.Row(subscription, found, found.size() >= FIND_LIMIT, inFlight));
        }
        return new SubscriptionSearch(effectiveFilter, messageId, rows);
    }
}
