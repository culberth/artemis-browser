package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
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
 * attributes, a scheduled-message read for the few queues that have any, and the connectivity reads — HA attributes,
 * the topology and broker-connection listings, a few attributes per bridge and cluster connection, and the prepared
 * transaction listings — details only for a bounded number of branches. Nothing browses a message body, so this page
 * costs the same on a broker with a 50,000-message dead-letter queue as on an empty one.
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
    private final ConnectivityService connectivityService;
    private final TransactionService transactionService;

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

    /**
     * Most addresses whose {@code blockedViaManagement} flag one page reads — one round trip each, since the listing
     * does not carry it. Past this the rest are named as not checked, so the page stays bounded on a broker with
     * thousands of addresses.
     */
    static final int BLOCK_CHECK_LIMIT = 500;

    /** How many of the largest addresses to name when global address memory is under pressure. */
    static final int MEMORY_HOLDERS = 3;

    public StuckDiagnosisService(QueueDirectory queueDirectory, AddressDirectory addressDirectory,
            BrokerInfoService brokerInfo, QueueBrowseService browseService, DivertDirectory divertDirectory,
            InFlightService inFlightService, RateService rateService, ConnectivityService connectivityService,
            TransactionService transactionService)
    {
        this.queueDirectory = queueDirectory;
        this.addressDirectory = addressDirectory;
        this.brokerInfo = brokerInfo;
        this.browseService = browseService;
        this.divertDirectory = divertDirectory;
        this.inFlightService = inFlightService;
        this.rateService = rateService;
        this.connectivityService = connectivityService;
        this.transactionService = transactionService;
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
        return run(includeInternal, null, null);
    }

    /**
     * The same, with connectivity already read — the incident snapshot reads it for its own section and hands it on,
     * rather than asking the broker twice. Null reads it here, from this run's queue listing. Transactions likewise.
     */
    public Diagnosis run(boolean includeInternal, Connectivity connectivity, Transactions transactions)
    {
        List<Finding> findings = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();
        BrokerHealth health = brokerInfo.health();
        brokerLevel(findings, unchecked, health);
        // The memory finding names the largest addresses, which are read below; its place is kept here,
        // with the broker's other findings.
        int memoryFindingAt = findings.size();

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
        Map<String, Reading<AddressSettings>> settings = new LinkedHashMap<>();
        // Before the queues: a message received in a prepared XA transaction stays on its queue as
        // delivering with no consumer, and a queue's finding should say what holds it.
        Transactions prepared = transactions != null ? transactions : transactionService.collect();
        for (QueueOverview queue : queues)
        {
            queueLevel(findings, unchecked, queue, consumers, rates, settings, prepared);
        }
        if (consumerReading.available())
        {
            hoarding(findings, queues, consumers);
        }
        Unread unread = longInFlight(findings, unchecked, queues, rates);
        Reading<List<AddressOverview>> addresses = Reading.attempt(addressDirectory::overview);
        if (addresses.available())
        {
            nowhereToGo(findings, unchecked, queues, addresses.value(), settings);
            addressLevel(findings, unchecked, includeInternal, addresses.value());
            addressPressure(findings, unchecked, includeInternal, addresses.value(), settings, health);
        }
        else
        {
            unchecked.add("Addresses — dropped messages, addresses with no queues, where killed or expired"
                    + " messages went, and which addresses are at their limits: " + addresses.explained());
        }
        // Paths leaving this broker and its replica, from the listing already read: a bridge's queue,
        // a cluster peer's store-and-forward queue and a mirror's queue are where their backlogs show.
        ConnectivityService.findings(connectivity != null ? connectivity : connectivityService.collect(Reading.of(all)),
                findings, unchecked);
        TransactionService.findings(prepared, Instant.now(), findings, unchecked);
        if (health.memoryPressure())
        {
            findings.add(memoryFindingAt, memoryPressure(health, addresses.orElse(List.of()), includeInternal));
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
            // Exclusive, or one consumer allowed: one consumer holding everything is the configuration
            // working. Measured on 2.55.0 and 2.57.0: two consumers on an exclusive queue held 20 and 0.
            if (queue.behavior().singleConsumer())
            {
                continue;
            }
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
            Reading<Long> groups = queueDirectory.groupCount(queue.name());
            String explanation = groups.available() && groups.value() > 0
                    ? groups.value() + " message group(s) are assigned on this queue, and every message of a group"
                            + " goes to the consumer holding it — so one consumer taking all the in-flight messages"
                            + " can be group affinity rather than a buffer."
                    : null;
            findings.add(Finding.watch("One consumer holds everything in flight on '" + queue.name() + "'",
                    who + " holds " + hoarder.deliveringCount() + " message(s) in flight, and the other " + others
                            + " consumer(s) on this queue hold none. In flight includes what sits in a consumer's"
                            + " client-side buffer, so one consumer can take a backlog the others could be working"
                            + " on — usually a consumer window or prefetch set too large for how slowly each message"
                            + " is processed.",
                    queue.name()).aboutClient(clientOf(clients.get(hoarder.sequentialId())), hoarder.connectionId())
                    .explainedBy(explanation));
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
    private void brokerLevel(List<Finding> findings, List<String> unchecked, BrokerHealth health)
    {
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
        // Only when the state was read: "could not read it" is in unchecked, and is not "stopped".
        if (health.stateKnown() && !health.running())
        {
            findings.add(Finding.stuck("The broker does not report itself as started",
                    "Server state is '" + health.state().value() + "'.", null));
        }
    }

    private void queueLevel(List<Finding> findings, List<String> unchecked, QueueOverview queue,
            List<BrokerConsumer> consumers, Rates rates, Map<String, Reading<AddressSettings>> settings,
            Transactions transactions)
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
            findings.add(unread(queue, transactions));
        }
        else if (queue.messageCount() > 0 && onlyBrowsersAttached(queue, consumers))
        {
            findings.add(Finding.stuck("Only browsers are attached to '" + queue.name() + "'",
                    queue.messageCount() + " message(s) waiting, and every consumer on this queue is browse-only. A"
                            + " browser reads copies and never takes a message, so the queue has consumers by the"
                            + " count and nothing that will ever drain it.",
                    queue.name()));
        }
        else if (queue.messageCount() > 0 && queue.deliveringCount() == 0
                && queue.behavior().dispatchGated(queue.consumerCount()))
        {
            long required = queue.behavior().consumersRequired();
            findings.add(Finding
                    .watch("'" + queue.name() + "' is waiting for " + required + " consumers before it dispatches",
                            queue.messageCount() + " message(s) waiting, " + queue.consumerCount() + " of the "
                                    + required + " consumers it needs attached, and none delivered.",
                            queue.name())
                    .explainedBy("The queue is configured with consumers-before-dispatch " + required
                            + ": it holds everything until that many consumers are attached. Measured on 2.55.0 and"
                            + " 2.57.0 with one of two attached: nothing was delivered."));
        }
        else if (queue.deliveringCount() > 0 && queue.messagesAcked() == 0)
        {
            Finding finding = Finding.watch("'" + queue.name() + "' has delivered nothing since the broker started",
                    queue.deliveringCount() + " message(s) are checked out to a consumer and none has been"
                            + " acknowledged. A consumer that takes messages and never acknowledges looks connected"
                            + " and healthy from every other angle.",
                    queue.name());
            findings.add(Boolean.TRUE.equals(nonDestructiveDefault(queue.address(), settings, unchecked))
                    ? finding.explainedBy("The address '" + queue.address() + "' makes new queues non-destructive"
                            + " by default. On a non-destructive queue consuming never counts as acknowledged —"
                            + " measured on 2.55.0 and 2.57.0 — so this is also what a working consumer looks like"
                            + " there. Whether this queue is one cannot be read per queue.")
                    : finding);
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
     * Messages on a queue no consumer is attached to. Usually they are waiting; but a message received in a prepared XA
     * transaction also stays on its queue, counted as delivering, with no consumer holding it — measured on 2.55.0 and
     * 2.57.0 — so delivering with no consumer is told apart, and linked to the transactions when they hold some.
     */
    private Finding unread(QueueOverview queue, Transactions transactions)
    {
        long waiting = Math.max(0, queue.messageCount() - queue.deliveringCount());
        long held = transactions.heldFrom(queue.address());
        String explanation = held == 0 ? null
                : held + " message(s) received from '" + queue.address() + "' are held by prepared XA transaction(s)."
                        + " They count as delivering on this queue until their transaction manager commits or rolls"
                        + " back, and neither browse nor the in-flight list shows them. The Transactions page lists"
                        + " them.";
        if (waiting == 0)
        {
            return Finding.stuck(
                    "'" + queue.name() + "' holds " + queue.deliveringCount() + " message(s) in delivery with no"
                            + " consumer attached",
                    "None is waiting for a consumer: all " + queue.messageCount() + " are counted as delivering, and"
                            + " no consumer is attached to hold them. A prepared XA transaction holds messages this"
                            + " way; so, for a moment, does a consumer that has just closed.",
                    queue.name()).explainedBy(explanation);
        }
        return Finding.stuck("Nothing is reading '" + queue.name() + "'", waiting
                + " message(s) waiting with no consumer attached"
                + (queue.deliveringCount() > 0
                        ? ", and " + queue.deliveringCount() + " more counted as delivering with none to" + " hold them"
                        : "")
                + ". Either the consumer is not running, or it is connected somewhere other than where you" + " think.",
                queue.name()).explainedBy(explanation);
    }

    /**
     * True when the queue has consumers but every one of them is a browser.
     *
     * <p>
     * A browse-only consumer counts towards {@code consumerCount} exactly like a real one, so the overview's "no
     * consumer" flag stays off while nothing is draining the queue. This tool's own browser is excluded — it is
     * attached for the length of a request, and reporting it would mean the page accused itself.
     */
    private Boolean nonDestructiveDefault(String address, Map<String, Reading<AddressSettings>> settings,
            List<String> unchecked)
    {
        boolean first = !settings.containsKey(address);
        Reading<AddressSettings> read = settings.computeIfAbsent(address,
                name -> Reading.attempt(() -> addressDirectory.settings(name)));
        if (!read.available())
        {
            if (first)
            {
                unchecked.add("Whether '" + address + "' makes its queues non-destructive: " + read.explained());
            }
            return null;
        }
        return read.value().nonDestructiveDefault();
    }

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
            List<AddressOverview> addresses, Map<String, Reading<AddressSettings>> settings)
    {
        Map<String, AddressOverview> byName = addresses.stream()
                .collect(Collectors.toMap(AddressOverview::name, address -> address, (a, b) -> a));
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
                if (problem != null && queue.behavior().purges())
                {
                    findings.add(Finding.watch(
                            "'" + queue.name() + "' has discarded " + queue.messagesKilled() + " killed message(s)",
                            queue.messagesKilled() + " message(s) were killed here, and " + problem + ", so they were"
                                    + " not kept — if the settings were the same when it happened.",
                            queue.name())
                            .explainedBy("The queue purges when its last consumer leaves, and a purge counts every"
                                    + " message it removes as killed — measured on 2.55.0 and 2.57.0. Some or all of"
                                    + " these may be purges rather than messages that failed delivery."));
                }
                else if (problem != null)
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
                Finding unrouted = Finding.atAddress(Finding.STUCK,
                        "'" + address.name() + "' has dropped " + address.unroutedMessageCount() + " message(s)",
                        "They were sent to the address and matched no queue, so they were discarded. Usually a"
                                + " subscriber that was never created, or a routing type that does not match the"
                                + " sender's.",
                        address.name());
                // Measured on 2.55.0 and 2.57.0: a queue that purges on no consumers refuses what is sent
                // while none is attached, and the address counts those as unrouted.
                List<String> purging = address.queues().stream().filter(queue -> queue.behavior().purges())
                        .map(QueueOverview::name).toList();
                findings.add(purging.isEmpty() ? unrouted
                        : unrouted.explainedBy("'" + String.join("', '", purging) + "' purges when it has no"
                                + " consumer, and refuses messages sent while none is attached — the address counts"
                                + " those as unrouted. Sends made between consumers are lost by design there."));
            }
            else if (address.queues().isEmpty() && !address.internal())
            {
                findings.add(Finding.atAddress(Finding.WATCH, "'" + address.name() + "' has no queues",
                        "Anything sent here now would be dropped rather than stored.", address.name()));
            }
        }
    }

    /**
     * Global address memory near {@code global-max-size}, with the addresses holding the most of it. Every address
     * shares that limit, so the largest are where the memory is — not necessarily why it filled, and the finding says
     * so rather than blame one.
     */
    private Finding memoryPressure(BrokerHealth health, List<AddressOverview> addresses, boolean includeInternal)
    {
        List<AddressOverview> largest = addresses.stream().filter(address -> includeInternal || !isBrokersOwn(address))
                .filter(address -> address.addressSize().available() && address.addressSize().value() > 0)
                .sorted(Comparator.comparing((AddressOverview address) -> address.addressSize().value()).reversed())
                .limit(MEMORY_HOLDERS).toList();
        String holders = largest.isEmpty() ? ""
                : " Largest holders: "
                        + largest.stream().map(address -> "'" + address.name() + "' " + address.sizeText())
                                .collect(Collectors.joining(", "))
                        + ". Every address shares this limit, so these are where the memory is, not necessarily"
                        + " why it filled.";
        String limit = health.globalMaxBytes().available()
                ? " of its " + AddressOverview.bytesText(health.globalMaxBytes().value()) + " limit"
                : "";
        return Finding.atAddress(Finding.STUCK, "The broker is near its address-memory limit",
                "Global address memory is at " + health.memoryUsedPercent().value() + "%" + limit
                        + ". Past it, every address applies its own policy: it pages, blocks producers, rejects"
                        + " sends or drops messages." + holders,
                largest.isEmpty() ? null : largest.get(0).name());
    }

    /**
     * Addresses an operator blocked, and addresses at or near a limit whose policy costs a sender something.
     *
     * <p>
     * The listing carries each address's size, limit percentage, page count and paging flag, so only addresses it
     * already shows near or over a limit, or writing pages, have their settings read — to learn the policy that turns a
     * percentage into a consequence. An address paging under PAGE is the policy working and gets no finding, unless a
     * page limit is close. The management block is the exception: the listing does not carry it, so it is read per
     * address, up to {@link #BLOCK_CHECK_LIMIT}.
     *
     * <p>
     * A block is reported as observed — the broker says so. Everything else is inferred: the broker reports the usage
     * and the policy, and what a producer is going through follows from those.
     */
    private void addressPressure(List<Finding> findings, List<String> unchecked, boolean includeInternal,
            List<AddressOverview> all, Map<String, Reading<AddressSettings>> settings, BrokerHealth health)
    {
        List<AddressOverview> addresses = all.stream().filter(address -> includeInternal || !isBrokersOwn(address))
                .toList();
        Map<String, Reading<Boolean>> blocked = blockedAddresses(unchecked, addresses);
        for (AddressOverview address : addresses)
        {
            Reading<Boolean> isBlocked = blocked.getOrDefault(address.name(),
                    Reading.notCollected("past the block-check limit"));
            if (isBlocked.available() && isBlocked.value())
            {
                findings.add(Finding.atAddress(Finding.STUCK, "'" + address.name() + "' is blocked by an operator",
                        "The broker reports it blocked through management: someone called block() on it. A"
                                + " producer sending here fails at once with \"address is full\", whatever its"
                                + " limits. It is lifted on the broker with unblock().",
                        address.name()));
                continue;
            }
            if (!address.underPressure() && !address.pagedToDisk())
            {
                continue;
            }
            boolean first = !settings.containsKey(address.name());
            Reading<AddressSettings> read = settings.computeIfAbsent(address.name(),
                    name -> Reading.attempt(() -> addressDirectory.settings(name)));
            if (!read.available())
            {
                if (first)
                {
                    unchecked.add("What happens at the limit of '" + address.name() + "': " + read.explained());
                }
                if (address.underPressure())
                {
                    findings.add(Finding.atAddress(Finding.WATCH, "'" + address.name() + "' is at or near its limit",
                            "The broker reports it " + usage(address) + ". Whether that pages, blocks, rejects or"
                                    + " drops depends on its policy, which could not be read.",
                            address.name()));
                }
                continue;
            }
            Finding finding = pressureFinding(new AddressPressure(address, read, isBlocked, health));
            if (finding != null)
            {
                findings.add(finding);
            }
        }
    }

    /**
     * {@code blockedViaManagement} for each address, stopping at {@link #BLOCK_CHECK_LIMIT} or at the first read the
     * broker will not give — a denial there is a denial for every address, and asking again per address would only
     * repeat it.
     */
    private Map<String, Reading<Boolean>> blockedAddresses(List<String> unchecked, List<AddressOverview> addresses)
    {
        Map<String, Reading<Boolean>> blocked = new LinkedHashMap<>();
        for (AddressOverview address : addresses)
        {
            if (blocked.size() >= BLOCK_CHECK_LIMIT)
            {
                unchecked.add("Whether " + (addresses.size() - BLOCK_CHECK_LIMIT) + " more address(es) are blocked by"
                        + " an operator: not collected, past the first " + BLOCK_CHECK_LIMIT);
                break;
            }
            Reading<Boolean> read = addressDirectory.blockedViaManagement(address.name());
            if (!read.available())
            {
                unchecked.add("Whether addresses are blocked by an operator (stopped at '" + address.name() + "'): "
                        + read.explained());
                break;
            }
            blocked.put(address.name(), read);
        }
        return blocked;
    }

    private Finding pressureFinding(AddressPressure pressure)
    {
        AddressOverview address = pressure.address();
        AddressSettings.FullPolicy policy = pressure.policy();
        return switch (pressure.state())
        {
            case AT_LIMIT -> Finding.atAddress(Finding.STUCK, atLimitTitle(address.name(), policy),
                    "The broker reports it " + usage(address) + limitsText(pressure) + ". Its policy is " + policy
                            + ": " + policy.consequence() + "." + dropEvidence(address, policy),
                    address.name()).inferred();
            case NEAR_LIMIT -> Finding.atAddress(Finding.WATCH, "'" + address.name() + "' is near its limit",
                    "The broker reports it " + usage(address) + limitsText(pressure) + ". When it gets there its"
                            + " policy, " + policy + ", means " + policy.consequence() + ".",
                    address.name()).inferred();
            case NEAR_PAGE_LIMIT ->
                Finding.atAddress(Finding.WATCH, "'" + address.name() + "' is paging and near its page limit",
                        "It has " + address.pages().value() + " page file(s) and a page limit of "
                                + pressure.pageLimitText() + ". Paging itself is normal; past the page limit "
                                + (pressure.pagePolicy() != null ? pressure.pagePolicy().consequence()
                                        : "the broker applies its page-full policy")
                                + ". How near is estimated on the high side: nothing reports the paged count itself.",
                        address.name()).inferred();
            default -> null;
        };
    }

    private static String atLimitTitle(String name, AddressSettings.FullPolicy policy)
    {
        return switch (policy)
        {
            case BLOCK -> "'" + name + "' is at its limit, so producers are made to wait";
            case FAIL -> "'" + name + "' is full, so sends to it are rejected";
            case DROP -> "'" + name + "' is full, so new messages are discarded";
            case PAGE -> "'" + name + "' is at its limit";
        };
    }

    /** "at 108% of its byte limit, holding 21.2 KB of address memory, 7 message(s)" — the listing, in its terms. */
    private static String usage(AddressOverview address)
    {
        List<String> parts = new ArrayList<>();
        if (address.measuredLimitPercent() != null)
        {
            parts.add("at " + address.measuredLimitPercent() + "% of its byte limit");
        }
        else if (address.fullWithoutPaging())
        {
            parts.add("over its limit");
        }
        if (address.sizeText() != null)
        {
            parts.add("holding " + address.sizeText() + " of address memory");
        }
        parts.add(address.messageCount() + " message(s)");
        return String.join(", ", parts);
    }

    private static String limitsText(AddressPressure pressure)
    {
        List<String> limits = new ArrayList<>();
        if (pressure.byteLimit() != null && pressure.byteLimit().limited())
        {
            limits.add(pressure.byteLimitText());
        }
        if (pressure.messageLimit() != null && pressure.messageLimit().limited())
        {
            limits.add(pressure.messageLimitText());
        }
        return limits.isEmpty() ? "" : " against a limit of " + String.join(" or ", limits);
    }

    /**
     * Under DROP the only trace of a discarded message is routed running ahead of what is held. Not a count of what was
     * dropped — consumers and expiry take messages too — so it is offered as context, never as a number dropped.
     */
    private static String dropEvidence(AddressOverview address, AddressSettings.FullPolicy policy)
    {
        if (policy != AddressSettings.FullPolicy.DROP || address.routedMessageCount() <= address.messageCount())
        {
            return "";
        }
        return " Since the broker started " + address.routedMessageCount() + " message(s) were routed here and "
                + address.messageCount() + " are held; the difference includes anything consumed or expired as well"
                + " as anything dropped.";
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
