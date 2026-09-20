package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 */
@Service
public class StuckDiagnosisService
{

    private final QueueDirectory queueDirectory;
    private final AddressDirectory addressDirectory;
    private final BrokerInfoService brokerInfo;
    private final QueueBrowseService browseService;

    public StuckDiagnosisService(QueueDirectory queueDirectory, AddressDirectory addressDirectory,
            BrokerInfoService brokerInfo, QueueBrowseService browseService)
    {
        this.queueDirectory = queueDirectory;
        this.addressDirectory = addressDirectory;
        this.brokerInfo = brokerInfo;
        this.browseService = browseService;
    }

    public List<Finding> diagnose(boolean includeInternal)
    {
        List<Finding> findings = new ArrayList<>();
        brokerLevel(findings);

        List<QueueOverview> queues = queueDirectory.overview();
        List<BrokerConsumer> consumers = brokerInfo.consumers();
        for (QueueOverview queue : queues)
        {
            if (queue.internalQueue() && !includeInternal)
            {
                continue;
            }
            queueLevel(findings, queue, consumers);
        }
        addressLevel(findings, includeInternal);

        // Whatever is not moving now first; within that, the biggest backlog.
        findings.sort((left, right) -> left.isStuck() == right.isStuck() ? 0 : (left.isStuck() ? -1 : 1));
        return findings;
    }

    /**
     * The broker refusing writes outranks anything a single queue is doing: when the disk or the address memory is at
     * its limit Artemis blocks producers, and every "nothing is arriving" report on the broker has the same cause.
     */
    private void brokerLevel(List<Finding> findings)
    {
        BrokerHealth health = brokerInfo.health();
        if (health.diskPressure())
        {
            findings.add(Finding.stuck("The broker is near its disk limit",
                    String.format(Locale.ROOT,
                            "Disk store is %.1f%% used against a %d%% limit. Artemis blocks"
                                    + " producers at that limit, so senders stall rather than fail.",
                            health.diskUsedPercent(), health.maxDiskPercent()),
                    null));
        }
        if (health.memoryPressure())
        {
            findings.add(Finding.stuck("The broker is near its address-memory limit",
                    "Global address memory is " + health.memoryUsedPercent() + "% used. Addresses at their limit page"
                            + " to disk or block producers, depending on how each is configured.",
                    null));
        }
        if (!health.running())
        {
            findings.add(Finding.stuck("The broker does not report itself as started",
                    "Server state is '" + health.state() + "'.", null));
        }
    }

    private void queueLevel(List<Finding> findings, QueueOverview queue, List<BrokerConsumer> consumers)
    {
        if (queue.paused())
        {
            findings.add(Finding.stuck("'" + queue.name() + "' is paused",
                    "A paused queue holds " + queue.messageCount() + " message(s) and delivers nothing until it is"
                            + " resumed. Pausing is done on the broker, not here.",
                    queue.name()));
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
            overdue(findings, queue);
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
    private void overdue(List<Finding> findings, QueueOverview queue)
    {
        long overdue = browseService.scheduled(queue.name()).stream().filter(ScheduledMessage::overdue).count();
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

    /** Messages that reached an address and matched no queue are gone, and nothing reports an error for them. */
    private void addressLevel(List<Finding> findings, boolean includeInternal)
    {
        for (AddressOverview address : addressDirectory.overview())
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

    private boolean looksLikeDeadLetter(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("dlq") || lower.contains("deadletter") || lower.contains("dead-letter")
                || lower.contains("expiry");
    }
}
