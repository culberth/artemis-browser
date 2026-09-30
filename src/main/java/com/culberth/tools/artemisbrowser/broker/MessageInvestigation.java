package com.culberth.tools.artemisbrowser.broker;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Where one message, by its exact ID, was seen — and where this tool looked, and could not.
 *
 * <p>
 * A message on a queue is in one of several states, and each is read a different way: waiting ones by {@code browse},
 * scheduled ones by {@code listScheduledMessagesAsJSON}, in-flight ones by {@code listDeliveringMessagesAsJSON}, and
 * ones received or sent inside a prepared XA branch only by {@code listPreparedTransactionDetailsAsJSON}. Each of those
 * is a separate read at a separate moment, and a message can move between them while the reads run. So every hit
 * carries the time it was seen, and the result is a set of observations, not a delivery history.
 *
 * <p>
 * The coverage is half the answer. A lookup that found nothing has only shown that the message was not seen where this
 * tool looked, at the moment it looked — so every queue and state that was skipped, denied, unavailable or over a
 * budget is reported, with why, rather than rounded into "not found".
 *
 * @param queuesListedAt   when the list of queues — and the counts every preflight decision used — was read
 * @param hits             every observation, in the order they were made
 * @param hitLimitReached  the result budget filled, so queues after that point were not looked at
 * @param coverage         one row per queue in scope, including those never reached
 * @param internalExcluded Artemis's own internal queues left out because they were not asked for
 * @param prepared         the check of prepared XA branches, which hold messages no queue read can see
 * @param inFlightRead     in-flight messages asked for across all queues, by the counts each read was allowed on,
 *                         against {@code budget.maxInFlight()}
 * @param scheduledRead    scheduled messages asked for likewise, against {@code budget.maxScheduled()}
 */
public record MessageInvestigation(String messageId, Instant startedAt, Instant finishedAt, Instant queuesListedAt,
        List<Hit> hits, boolean hitLimitReached, Budget budget, List<QueueCoverage> coverage, int internalExcluded,
        Check prepared, long inFlightRead, long scheduledRead)
{

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    /** Queues with incomplete coverage drawn on the page; the rest are counted. */
    public static final int INCOMPLETE_SHOWN = 200;

    /** The states a message can be observed in, in the order they are read — the order a message moves through them. */
    public enum State
    {
        SCHEDULED("scheduled", "held back until its delivery time"),
        WAITING("waiting", "on the queue, not yet delivered to a consumer"),
        IN_FLIGHT("in flight", "delivered to a consumer and not yet acknowledged"),
        PREPARED_RECEIVE("received in a prepared transaction",
                "still on its queue as delivering, with no consumer, until the transaction is resolved"),
        PREPARED_SEND("sent in a prepared transaction", "on no queue until the transaction commits");

        private final String label;
        private final String meaning;

        State(String label, String meaning)
        {
            this.label = label;
            this.meaning = meaning;
        }

        public String label()
        {
            return label;
        }

        public String meaning()
        {
            return meaning;
        }
    }

    /** What happened when one state of one queue was to be checked. */
    public enum Outcome
    {
        CHECKED("checked", true), NOTHING_THERE("nothing in this state when listed", true),
        PARTIAL("partly checked", false), SKIPPED("skipped", false), NOT_REACHED("not reached", false),
        DENIED("not permitted for this user", false), UNSUPPORTED("not supported by this broker", false),
        UNAVAILABLE("not available from this broker", false), FAILED("could not be read", false);

        private final String label;
        private final boolean complete;

        Outcome(String label, boolean complete)
        {
            this.label = label;
            this.complete = complete;
        }

        public String label()
        {
            return label;
        }

        /** Whether a message in this state would have been seen. */
        public boolean complete()
        {
            return complete;
        }

        static Outcome of(Availability availability)
        {
            return switch (availability)
            {
                case AVAILABLE -> CHECKED;
                case DENIED -> DENIED;
                case UNSUPPORTED -> UNSUPPORTED;
                case UNAVAILABLE -> UNAVAILABLE;
                case FAILED -> FAILED;
                case NOT_COLLECTED -> SKIPPED;
            };
        }
    }

    /**
     * @param detail why, in words — the limit that was hit, or the broker's own reason; empty when there is nothing to
     *               add
     * @param at     when the check was made or decided; null for one never reached
     */
    public record Check(Outcome outcome, String detail, Instant at)
    {

        public static Check checked(Instant at)
        {
            return new Check(Outcome.CHECKED, "", at);
        }

        public static Check nothingThere(String detail)
        {
            return new Check(Outcome.NOTHING_THERE, detail, Instant.now());
        }

        public static Check skipped(String detail)
        {
            return new Check(Outcome.SKIPPED, detail, Instant.now());
        }

        public static Check notReached(String detail)
        {
            return new Check(Outcome.NOT_REACHED, detail, null);
        }

        static Check of(Reading<?> reading)
        {
            return reading.available() ? checked(reading.collectedAt())
                    : new Check(Outcome.of(reading.availability()), reading.detail(), reading.collectedAt());
        }

        public boolean complete()
        {
            return outcome.complete();
        }

        public String atText()
        {
            return at == null ? "" : TIME.format(at);
        }

        /** "skipped — 9000 in flight, over artemis.in-flight-limit (5000)", for a sentence with room for both. */
        public String explained()
        {
            return detail.isBlank() ? outcome.label() : outcome.label() + " — " + detail;
        }
    }

    /** How one queue was covered, state by state. */
    public record QueueCoverage(String queueName, String address, Check scheduled, Check waiting, Check inFlight)
    {

        public boolean complete()
        {
            return scheduled.complete() && waiting.complete() && inFlight.complete();
        }

        public Check check(State state)
        {
            return switch (state)
            {
                case SCHEDULED -> scheduled;
                case WAITING -> waiting;
                case IN_FLIGHT -> inFlight;
                default -> throw new IllegalArgumentException(state + " is not read per queue");
            };
        }
    }

    /**
     * The limits one investigation ran under, all read before the call they bound, since none of the replies behind
     * scheduled, in-flight or prepared messages can be paged.
     *
     * @param maxQueues            queues looked at, at most
     * @param maxHits              observations kept; queues after the last one fits are not reached
     * @param inFlightLimit        in-flight messages one queue may hold for its delivering list to be read
     * @param maxInFlight          in-flight messages read across the whole investigation
     * @param maxScheduledPerQueue scheduled messages one queue may hold for its list to be read
     * @param maxScheduled         scheduled messages read across the whole investigation
     * @param transactionLimit     prepared branches whose detail is read
     */
    public record Budget(int maxQueues, int maxHits, int inFlightLimit, long maxInFlight, int maxScheduledPerQueue,
            long maxScheduled, int transactionLimit)
    {
    }

    /**
     * One sighting of the message.
     *
     * <p>
     * Exactly one of the per-state fields is set, the one {@code state} names; the others are null.
     *
     * @param queueName the queue it was on; null for a send in a prepared transaction, which is on none yet
     * @param address   the address it was sent to, which is how a prepared transaction names where it is
     * @param seenAt    when the read that saw it returned
     */
    public record Hit(State state, String queueName, String address, Instant seenAt, MessageSummary waiting,
            ScheduledMessage scheduled, InFlightConsumer holder, InFlightMessage inFlight, PreparedTransaction branch,
            TransactionMessage transactionMessage)
    {

        public static Hit waiting(String queueName, String address, Instant seenAt, MessageSummary message)
        {
            return new Hit(State.WAITING, queueName, address, seenAt, message, null, null, null, null, null);
        }

        public static Hit scheduled(String queueName, String address, Instant seenAt, ScheduledMessage message)
        {
            return new Hit(State.SCHEDULED, queueName, address, seenAt, null, message, null, null, null, null);
        }

        public static Hit inFlight(String queueName, String address, Instant seenAt, InFlightLookup lookup)
        {
            return new Hit(State.IN_FLIGHT, queueName, address, seenAt, null, null, lookup.holder(), lookup.message(),
                    null, null);
        }

        public static Hit prepared(Instant seenAt, PreparedTransaction branch, TransactionMessage message)
        {
            return new Hit(message.send() ? State.PREPARED_SEND : State.PREPARED_RECEIVE, null, message.address(),
                    seenAt, null, null, null, null, branch, message);
        }

        public String seenText()
        {
            return TIME.format(seenAt);
        }
    }

    public boolean found()
    {
        return !hits.isEmpty();
    }

    /** Whether every queue in scope, and the prepared branches, were looked at in every state. */
    public boolean complete()
    {
        return !hitLimitReached && prepared.complete() && coverage.stream().allMatch(QueueCoverage::complete);
    }

    public List<QueueCoverage> incomplete()
    {
        return coverage.stream().filter(row -> !row.complete()).toList();
    }

    public List<QueueCoverage> incompleteShown()
    {
        List<QueueCoverage> incomplete = incomplete();
        return incomplete.size() <= INCOMPLETE_SHOWN ? incomplete : incomplete.subList(0, INCOMPLETE_SHOWN);
    }

    /** How many queues ended in each outcome for one per-queue state, only outcomes that occurred. */
    public Map<Outcome, Long> tally(State state)
    {
        Map<Outcome, Long> tally = new EnumMap<>(Outcome.class);
        for (QueueCoverage row : coverage)
        {
            tally.merge(row.check(state).outcome(), 1L, Long::sum);
        }
        return tally;
    }

    /** The per-queue states, for the coverage table. */
    public List<State> queueStates()
    {
        return List.of(State.SCHEDULED, State.WAITING, State.IN_FLIGHT);
    }

    /** Distinct states the message was seen in — more than one means it moved, or was fanned out, while looked for. */
    public long statesSeen()
    {
        return hits.stream().map(Hit::state).distinct().count();
    }

    public long queuesSeenOn()
    {
        return hits.stream().map(Hit::queueName).filter(name -> name != null).distinct().count();
    }

    public String startedText()
    {
        return TIME.format(startedAt);
    }

    public String finishedText()
    {
        return TIME.format(finishedAt);
    }

    public String listedText()
    {
        return TIME.format(queuesListedAt);
    }

    public long tookMillis()
    {
        return Duration.between(startedAt, finishedAt).toMillis();
    }
}
