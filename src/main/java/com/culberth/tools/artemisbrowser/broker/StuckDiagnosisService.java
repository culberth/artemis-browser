package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Answers "why is this not moving" from what the broker already reports.
 *
 * <p>
 * The facts behind these findings were all visible before — spread over the overview, the broker page, the address view
 * and each queue's counters — which is exactly the problem at three in the morning. This gathers them into the question
 * someone actually has.
 *
 * <p>
 * Built from the cheap reads only: one {@code listQueues}, one consumer listing, one address listing, the health
 * attributes, and a scheduled-message read for the few queues that have any. Nothing browses a message body, so this
 * page costs the same on a broker with a 50,000-message dead-letter queue as on an empty one.
 *
 * <p>
 * The one exception is how long messages have been in flight, which only each queue's delivering list can say. Those
 * lists have no paging, so they are read within a budget of {@link #IN_FLIGHT_BUDGET_FACTOR} times
 * {@code artemis.in-flight-limit} messages for the whole page — about 20,000 by default, measured at roughly 5.6MB and
 * 150ms — smallest queue first, and whatever that leaves unread is reported on the page.
 */
@Service
public class StuckDiagnosisService
{

    private final QueueDirectory queueDirectory;
    private final AddressDirectory addressDirectory;
    private final BrokerInfoService brokerInfo;
    private final QueueBrowseService browseService;
    private final DivertDirectory divertDirectory;
    private final InFlightService inFlightService;
    private final RateService rateService;

    /**
     * Fewest in-flight messages one consumer must hold, with every other consumer on the queue holding none, to be
     * called hoarding. One message in flight while the rest sit idle is a consumer working; ten is a buffer.
     */
    static final long HOARDING_MIN = 10;

    /**
     * How old, from its send time, the oldest in-flight message must be to be worth a finding. Send time because
     * nothing reports a delivery time; ten minutes because a healthy consumer rarely holds one message that long.
     */
    static final long LONG_IN_FLIGHT_MILLIS = 10 * 60_000L;

    /** The page's in-flight reading budget, as a multiple of the per-queue limit. */
    static final int IN_FLIGHT_BUDGET_FACTOR = 4;

    public StuckDiagnosisService(QueueDirectory queueDirectory, AddressDirectory addressDirectory,
            BrokerInfoService brokerInfo, QueueBrowseService browseService, DivertDirectory divertDirectory,
            InFlightService inFlightService, RateService rateService)
    {
        this.queueDirectory = queueDirectory;
        this.addressDirectory = addressDirectory;
        this.brokerInfo = brokerInfo;
        this.browseService = browseService;
        this.divertDirectory = divertDirectory;
        this.inFlightService = inFlightService;
        this.rateService = rateService;
    }

    public List<Finding> diagnose(boolean includeInternal)
    {
        return run(includeInternal).findings();
    }

    /**
     * The findings, what the page did not read to reach them, and what it could not check.
     *
     * <p>
     * The queue listing is the page: without it there is nothing to diagnose, so its failure is the page's. Every other
     * read is guarded on its own and, when the broker will not give it, skips only the checks that needed it and says
     * so in {@link Diagnosis#unchecked()}. A page that says "nothing wrong" must have looked.
     */
    public Diagnosis run(boolean includeInternal)
    {
        List<Finding> findings = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();
        brokerLevel(findings, unchecked);

        List<QueueOverview> all = queueDirectory.overview();
        // Every queue, so the session's next reading on any page compares like with like.
        Diagnosis.Measured measured = rateService.forDiagnosis(all);
        Rates rates = measured.rates();
        List<QueueOverview> queues = all.stream().filter(queue -> includeInternal || !queue.internalQueue()).toList();
        Reading<List<BrokerConsumer>> consumerReading = Reading.attempt(brokerInfo::consumers);
        if (!consumerReading.available())
        {
            unchecked.add("Consumers — whether only browsers are attached, and whether one consumer holds everything"
                    + " in flight: " + consumerReading.explained());
        }
        List<BrokerConsumer> consumers = consumerReading.orElse(List.of());
        for (QueueOverview queue : queues)
        {
            queueLevel(findings, unchecked, queue, consumers, rates);
        }
        if (consumerReading.available())
        {
            hoarding(findings, queues, consumers);
        }
        Unread unread = longInFlight(findings, unchecked, queues, rates);
        Reading<List<AddressOverview>> addresses = Reading.attempt(addressDirectory::overview);
        if (addresses.available())
        {
            nowhereToGo(findings, unchecked, queues, addresses.value());
            addressLevel(findings, unchecked, includeInternal, addresses.value());
        }
        else
        {
            unchecked.add("Addresses — dropped messages, addresses with no queues, and where killed or expired"
                    + " messages went: " + addresses.explained());
        }

        // Whatever is not moving now first; within that, the biggest backlog.
        findings.sort((left, right) -> left.isStuck() == right.isStuck() ? 0 : (left.isStuck() ? -1 : 1));
        return new Diagnosis(findings, unread.queues(), unread.messages(), measured, unchecked);
    }

    /** In-flight messages the page's budget did not stretch to, and on how many queues. */
    private record Unread(int queues, long messages)
    {
    }

    /**
     * One consumer holding everything in flight while the others on the queue hold nothing.
     *
     * <p>
     * "In flight" includes a consumer's client-side buffer, and measured on 2.44.0 a CORE consumer on the default 1MB
     * window buffers about 3,200 small messages: consumer A received two, the third sat in A's buffer, and consumer B
     * on the same queue got nothing. From the counters that looks like two healthy consumers. Read from the consumer
     * listing diagnose already has, so it costs one more listing only when it fires — to name the client.
     */
    private void hoarding(List<Finding> findings, List<QueueOverview> queues, List<BrokerConsumer> consumers)
    {
        Map<QueueOverview, BrokerConsumer> hoarders = new LinkedHashMap<>();
        for (QueueOverview queue : queues)
        {
            List<BrokerConsumer> real = consumers.stream().filter(
                    consumer -> queue.name().equals(consumer.queueName()) && !consumer.browseOnly() && !consumer.self())
                    .toList();
            List<BrokerConsumer> holding = real.stream().filter(consumer -> consumer.deliveringCount() > 0).toList();
            if (real.size() >= 2 && holding.size() == 1 && holding.get(0).deliveringCount() >= HOARDING_MIN)
            {
                hoarders.put(queue, holding.get(0));
            }
        }
        if (hoarders.isEmpty())
        {
            return;
        }
        // Only to name the client; without it the finding names the consumer instead.
        Map<String, SubscriberConsumer> clients = Reading
                .attempt(() -> brokerInfo
                        .consumersOn(hoarders.keySet().stream().map(QueueOverview::name).collect(Collectors.toSet())))
                .orElse(List.of()).stream()
                .collect(Collectors.toMap(SubscriberConsumer::consumerId, client -> client, (a, b) -> a));
        hoarders.forEach((queue, hoarder) ->
        {
            long others = consumers.stream().filter(consumer -> queue.name().equals(consumer.queueName())
                    && !consumer.browseOnly() && !consumer.self() && !consumer.equals(hoarder)).count();
            String who = who(clients.get(hoarder.sequentialId()), hoarder);
            findings.add(Finding.watch("One consumer holds everything in flight on '" + queue.name() + "'",
                    who + " holds " + hoarder.deliveringCount() + " message(s) in flight, and the other " + others
                            + " consumer(s) on this queue hold none. In flight includes what sits in a consumer's"
                            + " client-side buffer, so one consumer can take a backlog the others could be working"
                            + " on — usually a consumer window or prefetch set too large for how slowly each message"
                            + " is processed.",
                    queue.name()).aboutClient(clientOf(clients.get(hoarder.sequentialId())), hoarder.connectionId()));
        });
    }

    /**
     * The oldest message still in flight on each queue, flagged when it was sent long ago.
     *
     * <p>
     * Measured from send time, because the broker reports no delivery time: on a queue with a backlog the message may
     * have waited most of that time before a consumer took it, and the finding says so rather than blaming the consumer
     * outright.
     */
    private Unread longInFlight(List<Finding> findings, List<String> unchecked, List<QueueOverview> queues, Rates rates)
    {
        long budget = (long) inFlightService.limit() * IN_FLIGHT_BUDGET_FACTOR;
        int queuesNotRead = 0;
        long notRead = 0;
        long now = System.currentTimeMillis();
        List<QueueOverview> candidates = queues.stream().filter(queue -> queue.deliveringCount() > 0)
                .sorted(Comparator.comparingLong(QueueOverview::deliveringCount)).toList();
        for (QueueOverview queue : candidates)
        {
            if (queue.deliveringCount() > budget || queue.deliveringCount() > inFlightService.limit())
            {
                queuesNotRead++;
                notRead += queue.deliveringCount();
                continue;
            }
            budget -= queue.deliveringCount();
            Reading<InFlightLookup> read = Reading
                    .attempt(() -> inFlightService.oldest(queue.name(), queue.deliveringCount()));
            if (!read.available())
            {
                unchecked.add("How long messages on '" + queue.name() + "' have been in flight: " + read.explained());
                continue;
            }
            InFlightLookup oldest = read.value();
            if (!oldest.found() || now - oldest.message().timestamp() < LONG_IN_FLIGHT_MILLIS)
            {
                continue;
            }
            long waiting = queue.messageCount() - queue.deliveringCount();
            findings.add(Finding
                    .watch("A message on '"
                            + queue.name() + "' sent " + AddressDetail.ageText(now - oldest.message().timestamp())
                            + " ago is still in flight",
                            oldest.message().messageId() + " is held, unacknowledged, by " + oldest.holder().describe()
                                    + ". A consumer stuck partway through a message looks like this. The age is from when the"
                                    + " message was sent — the broker does not report when it was delivered"
                                    + (waiting > 0 ? ", and this queue has " + waiting
                                            + " message(s) waiting, so it may have"
                                            + " spent most of that time in the backlog before a consumer took it."
                                            : ", so it is how old the message is, not how long this consumer has held it.")
                                    + moving(queue, rates),
                            queue.name())
                    .aboutClient(clientOf(oldest.holder().client()), oldest.holder().connectionId()));
        }
        return new Unread(queuesNotRead, notRead);
    }

    /**
     * Whether an abandoned subscription is still growing — the one thing its finding could only suspect from a single
     * snapshot.
     */
    private String growth(QueueOverview queue, Rates rates)
    {
        QueueRate rate = rates.of(queue.name());
        if (rate == null)
        {
            return "";
        }
        return rate.inPerSecond() > 0
                ? " It is still growing: " + rate.inText() + " message(s)/s over the last " + rates.intervalText() + "."
                : " Nothing was added to it in the last " + rates.intervalText() + ".";
    }

    /**
     * Whether the queue holding a long-in-flight message is acknowledging anything — which tells a consumer stuck on
     * one message among working ones, or a queue that has stopped, from a queue simply working through a backlog.
     */
    private String moving(QueueOverview queue, Rates rates)
    {
        QueueRate rate = rates.of(queue.name());
        if (rate == null)
        {
            return "";
        }
        return rate.ackedPerSecond() > 0
                ? " The queue acknowledged " + rate.ackedText() + " message(s)/s over the last " + rates.intervalText()
                        + ", so its consumers are working while this one is held."
                : " Nothing on this queue was acknowledged in the last " + rates.intervalText() + ".";
    }

    private static String clientOf(SubscriberConsumer client)
    {
        return client == null ? null : client.clientId();
    }

    private String who(SubscriberConsumer client, BrokerConsumer consumer)
    {
        if (client != null && !client.clientId().isEmpty())
        {
            return "Client '" + client.clientId() + "'"
                    + (client.remoteAddress().isEmpty() ? "" : " from " + client.remoteAddress());
        }
        return "Consumer " + consumer.key();
    }

    /**
     * The broker refusing writes outranks anything a single queue is doing: when the disk or the address memory is at
     * its limit Artemis blocks producers, and every "nothing is arriving" report on the broker has the same cause.
     */
    private void brokerLevel(List<Finding> findings, List<String> unchecked)
    {
        BrokerHealth health = brokerInfo.health();
        unchecked.addAll(health.unchecked());
        if (health.diskPressure())
        {
            findings.add(Finding.stuck("The broker is near its disk limit",
                    String.format(Locale.ROOT,
                            "Disk store is %.1f%% used against a %d%% limit. Artemis blocks"
                                    + " producers at that limit, so senders stall rather than fail.",
                            health.diskUsedPercent().value(), health.maxDiskPercent().value()),
                    null));
        }
        if (health.memoryPressure())
        {
            findings.add(Finding.stuck("The broker is near its address-memory limit",
                    "Global address memory is " + health.memoryUsedPercent().value()
                            + "% used. Addresses at their limit page"
                            + " to disk or block producers, depending on how each is configured.",
                    null));
        }
        // Only when the state was read: "could not read it" is in unchecked, and is not "stopped".
        if (health.stateKnown() && !health.running())
        {
            findings.add(Finding.stuck("The broker does not report itself as started",
                    "Server state is '" + health.state().value() + "'.", null));
        }
    }

    private void queueLevel(List<Finding> findings, List<String> unchecked, QueueOverview queue,
            List<BrokerConsumer> consumers, Rates rates)
    {
        if (queue.paused())
        {
            findings.add(Finding.stuck("'" + queue.name() + "' is paused",
                    "A paused queue holds " + queue.messageCount() + " message(s) and delivers nothing until it is"
                            + " resumed. Pausing is done on the broker, not here.",
                    queue.name()));
        }
        else if (queue.messageCount() > 0 && queue.consumerCount() == 0 && isDurableSubscription(queue))
        {
            findings.add(new Finding(Finding.STUCK,
                    "Durable subscription '" + queue.name() + "' has no subscriber attached",
                    queue.messageCount() + " message(s) kept for it. The broker stores a copy of every message sent"
                            + " to '" + queue.address() + "' for this subscription until its subscriber reconnects"
                            + " or the subscription is removed — so a subscriber that has gone for good leaves a"
                            + " queue that only grows, and on a busy address that is how a disk fills."
                            + growth(queue, rates),
                    queue.name(), queue.address()));
        }
        else if (queue.messageCount() > 0 && queue.consumerCount() == 0)
        {
            findings.add(Finding.stuck("Nothing is reading '" + queue.name() + "'",
                    queue.messageCount() + " message(s) waiting with no consumer attached. Either the consumer is not"
                            + " running, or it is connected somewhere other than where you think.",
                    queue.name()));
        }
        else if (queue.messageCount() > 0 && onlyBrowsersAttached(queue, consumers))
        {
            findings.add(Finding.stuck("Only browsers are attached to '" + queue.name() + "'",
                    queue.messageCount() + " message(s) waiting, and every consumer on this queue is browse-only. A"
                            + " browser reads copies and never takes a message, so the queue has consumers by the"
                            + " count and nothing that will ever drain it.",
                    queue.name()));
        }
        else if (queue.deliveringCount() > 0 && queue.messagesAcked() == 0)
        {
            findings.add(Finding.watch("'" + queue.name() + "' has delivered nothing since the broker started",
                    queue.deliveringCount() + " message(s) are checked out to a consumer and none has been"
                            + " acknowledged. A consumer that takes messages and never acknowledges looks connected"
                            + " and healthy from every other angle.",
                    queue.name()));
        }

        if (looksLikeDeadLetter(queue.name()) && queue.messageCount() > 0)
        {
            findings.add(Finding.watch("'" + queue.name() + "' is holding " + queue.messageCount() + " message(s)",
                    "Messages land here after they could not be delivered. Whatever sent them is likely still"
                            + " failing, and the originals are not coming back on their own.",
                    queue.name()));
        }

        if (queue.scheduledCount() > 0)
        {
            overdue(findings, unchecked, queue);
        }
    }

    /**
     * True when the queue has consumers but every one of them is a browser.
     *
     * <p>
     * A browse-only consumer counts towards {@code consumerCount} exactly like a real one, so the overview's "no
     * consumer" flag stays off while nothing is draining the queue. This tool's own browser is excluded — it is
     * attached for the length of a request, and reporting it would mean the page accused itself.
     */
    private boolean onlyBrowsersAttached(QueueOverview queue, List<BrokerConsumer> consumers)
    {
        List<BrokerConsumer> attached = consumers.stream()
                .filter(consumer -> queue.name().equals(consumer.queueName()) && !consumer.self()).toList();
        return !attached.isEmpty() && attached.stream().allMatch(BrokerConsumer::browseOnly);
    }

    /**
     * A scheduled message whose time has passed is a different problem from one that is merely waiting — it should have
     * been delivered and was not, and it is invisible in the message list either way.
     */
    private void overdue(List<Finding> findings, List<String> unchecked, QueueOverview queue)
    {
        Reading<List<ScheduledMessage>> scheduled = Reading.attempt(() -> browseService.scheduled(queue.name()));
        if (!scheduled.available())
        {
            unchecked.add("Whether the " + queue.scheduledCount() + " scheduled message(s) on '" + queue.name()
                    + "' are overdue: " + scheduled.explained());
            return;
        }
        long overdue = scheduled.value().stream().filter(ScheduledMessage::overdue).count();
        if (overdue > 0)
        {
            findings.add(Finding.stuck("'" + queue.name() + "' has " + overdue + " overdue scheduled message(s)",
                    "Their delivery time has passed and they are still scheduled. Scheduled messages are counted by"
                            + " the queue but never appear in a browse, so they are easy to miss entirely.",
                    queue.name()));
        }
        else
        {
            findings.add(Finding.watch(
                    "'" + queue.name() + "' is holding " + queue.scheduledCount() + " scheduled message(s)",
                    "Not a fault: the broker is holding them until their delivery time. They are not in the message"
                            + " list, which is why the count and the list disagree.",
                    queue.name()));
        }
    }

    /**
     * Messages killed or expired where there was nowhere to send them — gone, and reported by nothing else.
     *
     * <p>
     * Verified on 2.44.0: {@code messagesKilled} counts a message that exceeded max delivery attempts whether it was
     * then dead-lettered or dropped, and {@code messagesExpired} the same for the expiry address. The counter never
     * says which; the address's settings do. So a non-zero counter is looked up against them: no address set, one that
     * does not exist, or one with no queues to take the message. One settings read per affected address, only when a
     * counter is non-zero. The settings are today's, and the counters run from broker start, so the finding says "if
     * the settings were the same then".
     */
    private void nowhereToGo(List<Finding> findings, List<String> unchecked, List<QueueOverview> queues,
            List<AddressOverview> addresses)
    {
        Map<String, AddressOverview> byName = addresses.stream()
                .collect(Collectors.toMap(AddressOverview::name, address -> address, (a, b) -> a));
        Map<String, Reading<AddressSettings>> settings = new LinkedHashMap<>();
        for (QueueOverview queue : queues)
        {
            if (queue.messagesKilled() == 0 && queue.messagesExpired() == 0)
            {
                continue;
            }
            boolean first = !settings.containsKey(queue.address());
            Reading<AddressSettings> read = settings.computeIfAbsent(queue.address(),
                    address -> Reading.attempt(() -> addressDirectory.settings(address)));
            if (!read.available())
            {
                if (first)
                {
                    unchecked.add(
                            "Where killed or expired messages on '" + queue.address() + "' went: " + read.explained());
                }
                continue;
            }
            AddressSettings forAddress = read.value();
            if (queue.messagesKilled() > 0)
            {
                String problem = problem(forAddress.deadLetterAddress(), "dead-letter", byName);
                if (problem != null)
                {
                    findings.add(Finding.stuck(
                            "'" + queue.name() + "' has dropped " + queue.messagesKilled()
                                    + " message(s) after too many delivery attempts",
                            queue.messagesKilled() + " message(s) were killed here for exceeding their max delivery"
                                    + " attempts, and " + problem + ", so they were discarded rather than kept"
                                    + " for someone to look at — if the settings were the same when it happened. The"
                                    + " counter runs from broker start and does not say where a message went.",
                            queue.name()));
                }
            }
            if (queue.messagesExpired() > 0)
            {
                String problem = problem(forAddress.expiryAddress(), "expiry", byName);
                if (problem != null)
                {
                    findings.add(Finding.watch(
                            "'" + queue.name() + "' has dropped " + queue.messagesExpired() + " expired message(s)",
                            queue.messagesExpired() + " message(s) outlived their time to live here, and " + problem
                                    + ", so they were discarded — if the settings were the same when it happened."
                                    + " Often intended for messages that are worthless once stale; a loss if not.",
                            queue.name()));
                }
            }
        }
    }

    /** Why a message sent to {@code target} would be lost, or null when it would be kept. */
    private String problem(String target, String kind, Map<String, AddressOverview> addresses)
    {
        if (target == null)
        {
            return "the address's settings name no " + kind + " address";
        }
        AddressOverview address = addresses.get(target);
        if (address == null)
        {
            return "its " + kind + " address '" + target + "' does not exist";
        }
        if (address.queues().isEmpty())
        {
            return "its " + kind + " address '" + target + "' has no queues to hold them";
        }
        return null;
    }

    /** Messages that reached an address and matched no queue are gone, and nothing reports an error for them. */
    private void addressLevel(List<Finding> findings, List<String> unchecked, boolean includeInternal,
            List<AddressOverview> all)
    {
        Reading<List<Divert>> diverts = Reading.attempt(divertDirectory::all);
        if (diverts.available())
        {
            exclusiveDiverts(findings, diverts.value(),
                    all.stream().collect(Collectors.toMap(AddressOverview::name, address -> address, (a, b) -> a)));
        }
        else
        {
            unchecked.add("Exclusive diverts that take messages from subscribers: " + diverts.explained());
        }
        for (AddressOverview address : all)
        {
            if (isBrokersOwn(address) && !includeInternal)
            {
                continue;
            }
            if (address.hasUnrouted())
            {
                findings.add(Finding.atAddress(Finding.STUCK,
                        "'" + address.name() + "' has dropped " + address.unroutedMessageCount() + " message(s)",
                        "They were sent to the address and matched no queue, so they were discarded. Usually a"
                                + " subscriber that was never created, or a routing type that does not match the"
                                + " sender's.",
                        address.name()));
            }
            else if (address.queues().isEmpty() && !address.internal())
            {
                findings.add(Finding.atAddress(Finding.WATCH, "'" + address.name() + "' has no queues",
                        "Anything sent here now would be dropped rather than stored.", address.name()));
            }
        }
    }

    /**
     * Artemis's own addresses, which the inventory pages show on purpose and this one must not.
     *
     * <p>
     * {@code activemq.notifications} carries the broker's internal notifications and has no subscribers unless
     * something asks for them, so its unrouted count climbs on every broker from the moment it starts — 18 of them on a
     * broker that had existed for two minutes. Reporting that as dropped messages would put a permanent false alarm at
     * the top of this page, and a page that always says something is wrong is one nobody reads.
     */
    private boolean isBrokersOwn(AddressOverview address)
    {
        return address.internal() || address.name().startsWith("activemq.");
    }

    /**
     * A durable multicast queue not named after its address: a subscription someone made and may have walked away from.
     * A multicast queue configured on the broker under its address's own name is left to the general finding.
     */
    private boolean isDurableSubscription(QueueOverview queue)
    {
        return queue.durable() && "MULTICAST".equalsIgnoreCase(queue.routingType())
                && !queue.name().equals(queue.address());
    }

    /**
     * An exclusive divert takes a message instead of letting it route to the source address's own queues. On an address
     * with subscribers that means they never see what it matches — possibly intended, never reported.
     */
    private void exclusiveDiverts(List<Finding> findings, List<Divert> diverts, Map<String, AddressOverview> addresses)
    {
        for (Divert divert : diverts)
        {
            AddressOverview source = addresses.get(divert.address());
            if (!divert.exclusive() || source == null || source.queues().isEmpty())
            {
                continue;
            }
            String what = divert.filtered() ? "every message matching " + divert.filter() : "every message";
            findings.add(Finding.atAddress(Finding.WATCH,
                    "'" + divert.name() + "' takes " + what + " sent to '" + divert.address() + "'",
                    "It is an exclusive divert to '" + divert.forwardingAddress() + "', so the "
                            + source.queues().size() + " queue(s) on '" + divert.address() + "' never receive what it"
                            + " matches, and nothing reports that as an error. Intended if '" + divert.address()
                            + "' is only an entry point; a mystery if its subscribers are waiting for those messages.",
                    divert.address()));
        }
    }

    private boolean looksLikeDeadLetter(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("dlq") || lower.contains("deadletter") || lower.contains("dead-letter")
                || lower.contains("expiry");
    }
}
