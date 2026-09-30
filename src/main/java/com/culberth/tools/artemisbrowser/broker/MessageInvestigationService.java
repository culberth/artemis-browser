package com.culberth.tools.artemisbrowser.broker;

import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.Budget;
import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.Check;
import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.Hit;
import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.QueueCoverage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Looks for one message, by its exact ID, in every state a queue can hold it in — "where is my message?".
 *
 * <p>
 * An exact ID needs no filter evaluation, only a string comparison, which is what lets the lists that cannot be
 * filtered be searched at all ({@link MessageIdLookup}). Verified on 2.55.0 and 2.57.0: browse's {@code userID}, the
 * scheduled list's, the delivering list's and a prepared branch's are all the sender's {@code JMSMessageID}.
 *
 * <p>
 * Each queue is read in the order a message moves through its states: scheduled, then waiting, then in flight. A
 * message that moves forward while being looked for is then seen at least once — possibly twice, which the page says —
 * unless it is acknowledged, expired or moved to another queue in the gap. Moving backwards (a rollback returning an
 * in-flight message to waiting) can slip between two reads, and nothing here can prevent that; it is why a miss is
 * never presented as proof. Prepared XA branches are read last, once, since a received message reaches one only after
 * it was in flight.
 *
 * <p>
 * Budgets, all applied before the call they bound: the scheduled, delivering and prepared-detail replies cannot be
 * paged, so the counts from the queue listing decide whether a read is affordable, and a read that is not is recorded
 * as skipped with the limit that stopped it.
 *
 * <p>
 * Read-only: browse and the three list operations leave every counter unchanged, verified against a real broker.
 */
@Service
public class MessageInvestigationService
{

    /** Rows one queue's browse may return. One is expected; more means the ID was reused. */
    static final int WAITING_PER_QUEUE = 5;

    private final QueueDirectory queueDirectory;
    private final QueueBrowseService browseService;
    private final InFlightService inFlightService;
    private final TransactionService transactionService;
    private final int maxQueues;
    private final int maxHits;
    private final long maxInFlight;
    private final int maxScheduledPerQueue;
    private final long maxScheduled;

    public MessageInvestigationService(QueueDirectory queueDirectory, QueueBrowseService browseService,
            InFlightService inFlightService, TransactionService transactionService,
            @Value("${artemis.investigate.max-queues:2000}") int maxQueues,
            @Value("${artemis.investigate.max-hits:100}") int maxHits,
            @Value("${artemis.investigate.max-in-flight:20000}") long maxInFlight,
            @Value("${artemis.investigate.max-scheduled-per-queue:5000}") int maxScheduledPerQueue,
            @Value("${artemis.investigate.max-scheduled:20000}") long maxScheduled)
    {
        this.queueDirectory = queueDirectory;
        this.browseService = browseService;
        this.inFlightService = inFlightService;
        this.transactionService = transactionService;
        this.maxQueues = maxQueues;
        this.maxHits = maxHits;
        this.maxInFlight = maxInFlight;
        this.maxScheduledPerQueue = maxScheduledPerQueue;
        this.maxScheduled = maxScheduled;
    }

    public Budget budget()
    {
        return new Budget(maxQueues, maxHits, inFlightService.limit(), maxInFlight, maxScheduledPerQueue, maxScheduled,
                transactionService.detailLimit());
    }

    /**
     * @param messageId       an exact {@code ID:…}, as {@link MessageIdLookup#messageId} returns it
     * @param includeInternal whether Artemis's own internal queues are in scope
     */
    public MessageInvestigation investigate(String messageId, boolean includeInternal)
    {
        if (messageId == null || messageId.isBlank() || messageId.indexOf('\'') >= 0)
        {
            throw new BrokerException("Enter a message ID (ID:…) to look one message up.");
        }
        Budget budget = budget();
        Instant started = Instant.now();
        List<QueueOverview> queues = queueDirectory.overview();
        Instant listed = Instant.now();
        String filter = "AMQUserID = '" + messageId + "'";

        List<Hit> hits = new ArrayList<>();
        List<QueueCoverage> coverage = new ArrayList<>();
        int internalExcluded = 0;
        int looked = 0;
        long inFlightRead = 0;
        long scheduledRead = 0;
        boolean hitLimitReached = false;

        for (QueueOverview queue : queues)
        {
            if (queue.internalQueue() && !includeInternal)
            {
                internalExcluded++;
                continue;
            }
            if (hitLimitReached)
            {
                coverage.add(unreached(queue, "the result limit (" + budget.maxHits()
                        + ", artemis.investigate.max-hits) filled before this queue"));
                continue;
            }
            if (looked >= budget.maxQueues())
            {
                coverage.add(unreached(queue,
                        "over the queue limit (" + budget.maxQueues() + ", artemis.investigate.max-queues)"));
                continue;
            }
            looked++;

            // Scheduled first, then waiting, then in flight: the order a message moves through them.
            Check scheduled;
            if (queue.scheduledCount() <= 0)
            {
                scheduled = Check.nothingThere("");
            }
            else if (queue.scheduledCount() > budget.maxScheduledPerQueue())
            {
                scheduled = Check.skipped(queue.scheduledCount() + " scheduled, over the per-queue limit ("
                        + budget.maxScheduledPerQueue() + ", artemis.investigate.max-scheduled-per-queue); the broker"
                        + " returns them all in one reply");
            }
            else if (scheduledRead + queue.scheduledCount() > budget.maxScheduled())
            {
                scheduled = Check.skipped(queue.scheduledCount() + " scheduled would pass this lookup's budget ("
                        + budget.maxScheduled() + ", artemis.investigate.max-scheduled)");
            }
            else
            {
                // Charged at the count it was allowed on, as the in-flight budget is: the reply may
                // be smaller by now, but the decision to ask was made on this number.
                scheduledRead += queue.scheduledCount();
                Reading<List<ScheduledMessage>> read = Reading.attempt(() -> browseService.scheduled(queue.name()));
                scheduled = Check.of(read);
                if (read.available())
                {
                    for (ScheduledMessage message : read.value())
                    {
                        if (messageId.equals(message.messageId()))
                        {
                            hits.add(Hit.scheduled(queue.name(), queue.address(), read.collectedAt(), message));
                        }
                    }
                }
            }

            int room = Math.max(1, Math.min(WAITING_PER_QUEUE, budget.maxHits() - hits.size()));
            Reading<List<MessageSummary>> browsed = Reading
                    .attempt(() -> browseService.matching(queue.name(), filter, room));
            Check waiting = Check.of(browsed);
            if (browsed.available())
            {
                for (MessageSummary message : browsed.value())
                {
                    hits.add(Hit.waiting(queue.name(), queue.address(), browsed.collectedAt(), message));
                }
            }

            Check inFlight;
            if (queue.deliveringCount() <= 0)
            {
                inFlight = Check.nothingThere("");
            }
            else if (queue.deliveringCount() > budget.inFlightLimit())
            {
                inFlight = Check.skipped(
                        queue.deliveringCount() + " in flight, over the per-queue limit (" + budget.inFlightLimit()
                                + ", artemis.in-flight-limit); the broker returns them all in one" + " reply");
            }
            else if (inFlightRead + queue.deliveringCount() > budget.maxInFlight())
            {
                inFlight = Check.skipped(queue.deliveringCount() + " in flight would pass this lookup's budget ("
                        + budget.maxInFlight() + ", artemis.investigate.max-in-flight)");
            }
            else
            {
                inFlightRead += queue.deliveringCount();
                Reading<InFlightLookup> read = Reading
                        .attempt(() -> inFlightService.locate(queue.name(), queue.deliveringCount(), messageId));
                if (!read.available())
                {
                    inFlight = Check.of(read);
                }
                else if (read.value().found())
                {
                    inFlight = Check.checked(read.collectedAt());
                    hits.add(Hit.inFlight(queue.name(), queue.address(), read.collectedAt(), read.value()));
                }
                else if (read.value().checked())
                {
                    inFlight = Check.checked(read.collectedAt());
                }
                else
                {
                    // Fewer than the limit when listed, more by the time the list came back.
                    inFlight = new Check(
                            MessageInvestigation.Outcome.PARTIAL, "the delivering list had grown past the"
                                    + " per-queue limit (" + budget.inFlightLimit() + ") and was cut there",
                            read.collectedAt());
                }
            }

            coverage.add(new QueueCoverage(queue.name(), queue.address(), scheduled, waiting, inFlight));
            hitLimitReached = hits.size() >= budget.maxHits();
        }

        Check prepared = prepared(messageId, hits, budget);
        return new MessageInvestigation(messageId, started, Instant.now(), listed, List.copyOf(hits), hitLimitReached,
                budget, List.copyOf(coverage), internalExcluded, prepared, inFlightRead, scheduledRead);
    }

    /**
     * Prepared XA branches: the only place a message received in one can be seen — it stays on its queue as delivering,
     * with no consumer, and is in neither browse nor the delivering list — and the only place a message sent in one
     * exists at all until it commits.
     */
    private Check prepared(String messageId, List<Hit> hits, Budget budget)
    {
        if (hits.size() >= budget.maxHits())
        {
            return Check.notReached("the result limit (" + budget.maxHits() + ") filled first");
        }
        Transactions transactions = transactionService.collect();
        Reading<List<PreparedTransaction>> read = transactions.prepared();
        if (!read.available())
        {
            return Check.of(read);
        }
        if (!transactions.detailRead())
        {
            return new Check(MessageInvestigation.Outcome.SKIPPED,
                    transactions.preparedTotal() + " branches are prepared, more than the " + budget.transactionLimit()
                            + " whose messages are read (artemis.transactions.detail-limit); the broker returns every"
                            + " branch's messages in one reply",
                    read.collectedAt());
        }
        int cut = 0;
        for (PreparedTransaction branch : read.value())
        {
            for (TransactionMessage message : branch.messages())
            {
                if (messageId.equals(message.userId()) && hits.size() < budget.maxHits())
                {
                    hits.add(Hit.prepared(read.collectedAt(), branch, message));
                }
            }
            if (branch.truncated())
            {
                cut++;
            }
        }
        if (cut > 0)
        {
            return new Check(MessageInvestigation.Outcome.PARTIAL, cut + " branch(es) held more messages than are"
                    + " kept per branch, and the rest were not compared", read.collectedAt());
        }
        return new Check(MessageInvestigation.Outcome.CHECKED,
                read.value().isEmpty() ? "none prepared" : read.value().size() + " branch(es) read",
                read.collectedAt());
    }

    private static QueueCoverage unreached(QueueOverview queue, String why)
    {
        Check check = Check.notReached(why);
        return new QueueCoverage(queue.name(), queue.address(), check, check, check);
    }
}
