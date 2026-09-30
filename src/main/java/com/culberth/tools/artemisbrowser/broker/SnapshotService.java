package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Collects an {@link IncidentSnapshot}: each section read in turn, each allowed to fail on its own.
 *
 * <p>
 * Built from reads the pages already make — the queue and address listings, the health attributes, the client listings
 * — plus address settings for a bounded set of addresses and one diagnose run. Nothing browses a message, so nothing
 * here touches a body. The queue listing is read once and handed to the trend tracker as this session's next reading,
 * so the snapshot's trend section includes the snapshot's own moment.
 *
 * <p>
 * Bounded: each client listing keeps at most {@code artemis.snapshot.max-rows} rows and says how many there were;
 * address settings are read for at most {@code artemis.snapshot.max-address-settings} addresses, the ones with
 * something to explain first; trends are already bounded by their own settings. A lost connection still ends the
 * collection, as it ends a page.
 */
@Service
public class SnapshotService
{

    private final BrokerSession brokerSession;
    private final BrokerInfoService brokerInfo;
    private final QueueDirectory queueDirectory;
    private final AddressDirectory addressDirectory;
    private final RateService rateService;
    private final StuckDiagnosisService diagnosis;
    private final int maxRows;
    private final int maxAddressSettings;

    public SnapshotService(BrokerSession brokerSession, BrokerInfoService brokerInfo, QueueDirectory queueDirectory,
            AddressDirectory addressDirectory, RateService rateService, StuckDiagnosisService diagnosis,
            @Value("${artemis.snapshot.max-rows:1000}") int maxRows,
            @Value("${artemis.snapshot.max-address-settings:200}") int maxAddressSettings)
    {
        this.brokerSession = brokerSession;
        this.brokerInfo = brokerInfo;
        this.queueDirectory = queueDirectory;
        this.addressDirectory = addressDirectory;
        this.rateService = rateService;
        this.diagnosis = diagnosis;
        this.maxRows = Math.max(1, maxRows);
        this.maxAddressSettings = Math.max(0, maxAddressSettings);
    }

    public IncidentSnapshot collect()
    {
        Instant started = Instant.now();
        ConnectionInfo connection = brokerSession.info();
        BrokerHealth health = brokerInfo.health();

        Reading<List<QueueOverview>> queues = Reading.attempt(queueDirectory::overview);
        if (queues.available())
        {
            rateService.observe(queues.value());
        }
        Reading<List<AddressOverview>> addresses = Reading.attempt(addressDirectory::overview);

        Map<String, Reading<AddressSettings>> settings = new LinkedHashMap<>();
        int omitted = 0;
        if (addresses.available())
        {
            List<AddressOverview> chosen = worthExplaining(addresses.value(), queues.orElse(List.of()));
            for (AddressOverview address : chosen)
            {
                if (settings.size() >= maxAddressSettings)
                {
                    omitted++;
                    continue;
                }
                settings.put(address.name(), Reading.attempt(() -> addressDirectory.settings(address.name())));
            }
        }

        IncidentSnapshot.Listing<AcceptorInfo> acceptors = IncidentSnapshot.Listing
                .of(Reading.attempt(brokerInfo::acceptors), maxRows);
        IncidentSnapshot.Listing<BrokerConnection> connections = IncidentSnapshot.Listing
                .of(Reading.attempt(brokerInfo::connections), maxRows);
        IncidentSnapshot.Listing<BrokerConsumer> consumers = IncidentSnapshot.Listing
                .of(Reading.attempt(brokerInfo::consumers), maxRows);
        IncidentSnapshot.Listing<BrokerProducer> producers = IncidentSnapshot.Listing
                .of(Reading.attempt(brokerInfo::producers), maxRows);

        Reading<Diagnosis> found = Reading.attempt(() -> diagnosis.run(false));
        Trends trends = rateService.trends();

        return new IncidentSnapshot(started, Instant.now(), connection, health, queues, addresses, settings, omitted,
                acceptors, connections, consumers, producers, trends, found, new IncidentSnapshot.Limits(maxRows,
                        maxAddressSettings, trends.spacingMillis(), trends.maxReadings(), trends.maxQueues()));
    }

    /**
     * Every address, the ones with something to explain first: near or over a limit, paging, dropping unrouted
     * messages, or holding a queue that killed or expired messages — the questions address settings answer. Then the
     * rest by name. The broker's own addresses come last.
     */
    static List<AddressOverview> worthExplaining(List<AddressOverview> addresses, List<QueueOverview> queues)
    {
        Set<String> losing = queues.stream().filter(queue -> queue.messagesKilled() > 0 || queue.messagesExpired() > 0)
                .map(QueueOverview::address).collect(Collectors.toSet());
        Comparator<AddressOverview> order = Comparator
                .comparing((AddressOverview address) -> address.internal() || address.name().startsWith("activemq."))
                .thenComparing(address -> !(address.underPressure() || address.paging() || address.hasUnrouted()
                        || losing.contains(address.name())))
                .thenComparing(AddressOverview::name);
        return addresses.stream().sorted(order).toList();
    }
}
