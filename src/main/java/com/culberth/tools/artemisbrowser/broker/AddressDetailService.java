package com.culberth.tools.artemisbrowser.broker;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Assembles one address: its counters, its subscriptions, the consumers on each, and the producers sending to it.
 *
 * <p>
 * Four cheap listings, none per-queue: the address listing (through {@link AddressDirectory}), a {@code listQueues}
 * filtered to the address, {@code listConsumers}, and {@code listProducersInfoAsJSON}.
 */
@Service
public class AddressDetailService
{

    private final AddressDirectory addressDirectory;
    private final QueueDirectory queueDirectory;
    private final BrokerInfoService brokerInfo;

    public AddressDetailService(AddressDirectory addressDirectory, QueueDirectory queueDirectory,
            BrokerInfoService brokerInfo)
    {
        this.addressDirectory = addressDirectory;
        this.queueDirectory = queueDirectory;
        this.brokerInfo = brokerInfo;
    }

    /** The address by exact name, or null when the broker has no such address. */
    public AddressDetail detail(String name)
    {
        AddressOverview address = addressDirectory.find(name);
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
        return new AddressDetail(address, subscriptions, consumers, producers);
    }
}
