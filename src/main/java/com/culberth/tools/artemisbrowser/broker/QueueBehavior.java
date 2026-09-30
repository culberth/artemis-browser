package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * What a queue is configured to do, as its {@code listQueues} row reports it, and what that looks like from outside.
 *
 * <p>
 * The listing reports each queue's <em>effective</em> values — an explicit setting, or the address default it was
 * created with. Verified on 2.55.0 and 2.57.0 (shapes in {@code .claude/memory.md}), and three things there are not
 * what they seem:
 * <ul>
 * <li>A queue made with a last-value key reports {@code lastValue: "false"} and behaves as last-value all the same, so
 * the key being set is the signal, never the flag.</li>
 * <li>Messages a last-value queue replaces and a ring queue evicts appear in no counter: added keeps rising, held stays
 * small, nothing is killed or acknowledged.</li>
 * <li>Non-destructive is reported nowhere per queue — not in the listing, not as an attribute. It is
 * {@link Availability#UNSUPPORTED} here, and the address default is shown only as a default.</li>
 * </ul>
 */
public record QueueBehavior(Reading<String> lastValueKey, Reading<Long> ringSize, Reading<Boolean> exclusive,
        Reading<Long> maxConsumers, Reading<Boolean> purgeOnNoConsumers, Reading<Long> consumersBeforeDispatch,
        Reading<Long> delayBeforeDispatch, Reading<Boolean> groupRebalance, Reading<Long> groupBuckets,
        Reading<String> groupFirstKey, Reading<Boolean> enabled, Reading<Boolean> nonDestructive)
{

    private static final String LISTING = "queue listing";

    /** Nothing read — for rows built from counters alone, in tests and older callers. */
    public static final QueueBehavior NOT_COLLECTED = new QueueBehavior(Reading.notCollected("not read"),
            Reading.notCollected("not read"), Reading.notCollected("not read"), Reading.notCollected("not read"),
            Reading.notCollected("not read"), Reading.notCollected("not read"), Reading.notCollected("not read"),
            Reading.notCollected("not read"), Reading.notCollected("not read"), Reading.notCollected("not read"),
            Reading.notCollected("not read"), Reading.notCollected("not read"));

    public static QueueBehavior from(JsonNode node)
    {
        return new QueueBehavior(ListingFields.text(node, "lastValueKey", LISTING),
                ListingFields.number(node, "ringSize", LISTING), ListingFields.flag(node, "exclusive", LISTING),
                ListingFields.number(node, "maxConsumers", LISTING),
                ListingFields.flag(node, "purgeOnNoConsumers", LISTING),
                ListingFields.number(node, "consumersBeforeDispatch", LISTING),
                ListingFields.number(node, "delayBeforeDispatch", LISTING),
                ListingFields.flag(node, "groupRebalance", LISTING),
                ListingFields.number(node, "groupBuckets", LISTING), ListingFields.text(node, "groupFirstKey", LISTING),
                ListingFields.flag(node, "enabled", LISTING),
                // Checked on 2.55.0 and 2.57.0: neither the listing nor the queue's attributes carry it.
                Reading.missing(Availability.UNSUPPORTED, "not reported per queue by this broker"));
    }

    /** Last-value on this key, or null. The key, not the {@code lastValue} flag, which reads false regardless. */
    public String lastValueOn()
    {
        return lastValueKey.available() && !lastValueKey.value().isBlank() ? lastValueKey.value() : null;
    }

    /** The ring size, or null when the queue is not a ring ({@code -1}). */
    public Long ring()
    {
        return ringSize.available() && ringSize.value() > 0 ? ringSize.value() : null;
    }

    /** One consumer at a time receives everything: exclusive, or at most one consumer allowed. */
    public boolean singleConsumer()
    {
        return isTrue(exclusive) || (maxConsumers.available() && maxConsumers.value() == 1);
    }

    public boolean purges()
    {
        return isTrue(purgeOnNoConsumers);
    }

    /** How many consumers must attach before anything is dispatched; 0 when none are required. */
    public long consumersRequired()
    {
        return consumersBeforeDispatch.available() ? Math.max(0, consumersBeforeDispatch.value()) : 0;
    }

    /** Consumers attached, but fewer than the queue waits for — so nothing is dispatched, by design. */
    public boolean dispatchGated(int consumerCount)
    {
        return consumerCount > 0 && consumerCount < consumersRequired();
    }

    public boolean disabled()
    {
        return enabled.available() && !enabled.value();
    }

    /** Anything other than a plain first-in, first-out queue. */
    public boolean special()
    {
        return !effects().isEmpty();
    }

    /** True when at least one setting was read — false for a row built from counters alone. */
    public boolean known()
    {
        return ringSize.available() || exclusive.available() || lastValueKey.available();
    }

    /**
     * Each setting away from its default, with what it looks like from outside. The wording follows what was measured,
     * not what the documentation promises: dispatch delay is stated as a setting, not a time, because on 2.55.0 and
     * 2.57.0 it did not release dispatch in the time it names.
     */
    public List<Effect> effects()
    {
        List<Effect> effects = new ArrayList<>();
        if (lastValueOn() != null)
        {
            effects.add(new Effect("Last-value", "key " + lastValueOn(),
                    "A new message replaces the waiting one with the same " + lastValueOn()
                            + " value, so fewer are held than were sent. Replaced messages appear in no counter."));
        }
        if (ring() != null)
        {
            effects.add(new Effect("Ring", ring() + " messages", "Once " + ring()
                    + " are held, each new message removes the oldest. Removed messages appear in no" + " counter."));
        }
        if (isTrue(exclusive))
        {
            effects.add(new Effect("Exclusive", "yes",
                    "One consumer receives everything; any others attached wait as standbys and receive nothing."));
        }
        if (maxConsumers.available() && maxConsumers.value() >= 0)
        {
            effects.add(new Effect("Max consumers", String.valueOf(maxConsumers.value()),
                    maxConsumers.value() == 0 ? "No consumer may attach."
                            : "A consumer beyond " + maxConsumers.value() + " is refused."));
        }
        if (purges())
        {
            effects.add(new Effect("Purge on no consumers", "yes",
                    "When the last consumer leaves, every message on the queue is removed and counted as killed, and"
                            + " messages sent while no consumer is attached are not kept."));
        }
        if (consumersRequired() > 0)
        {
            effects.add(new Effect("Consumers before dispatch", String.valueOf(consumersRequired()),
                    "Nothing is delivered until " + consumersRequired() + " consumers are attached."));
        }
        if (delayBeforeDispatch.available() && delayBeforeDispatch.value() >= 0)
        {
            effects.add(new Effect("Delay before dispatch", delayBeforeDispatch.value() + "ms",
                    "Meant to let dispatch start after this delay without the required consumers; not observed to"
                            + " do so promptly, so it is shown as configured rather than as a time."));
        }
        if (isTrue(groupRebalance))
        {
            effects.add(new Effect("Group rebalance", "yes",
                    "Message groups are reassigned across consumers when a consumer joins."));
        }
        if (groupBuckets.available() && groupBuckets.value() > 0)
        {
            effects.add(new Effect("Group buckets", String.valueOf(groupBuckets.value()), "Groups are hashed into "
                    + groupBuckets.value() + " buckets, so unrelated groups can share a consumer."));
        }
        if (groupFirstKey.available() && !groupFirstKey.value().isBlank())
        {
            effects.add(new Effect("Group first key", groupFirstKey.value(),
                    "The first message of each group carries this property."));
        }
        if (disabled())
        {
            effects.add(new Effect("Disabled", "yes", "Messages sent to its address are not routed to it."));
        }
        return effects;
    }

    /** Short labels for a badge row — "last-value", "ring 3", "exclusive", "waits for 2 consumers". */
    public List<String> badges()
    {
        List<String> badges = new ArrayList<>();
        if (lastValueOn() != null)
        {
            badges.add("last-value");
        }
        if (ring() != null)
        {
            badges.add("ring " + ring());
        }
        if (isTrue(exclusive))
        {
            badges.add("exclusive");
        }
        if (purges())
        {
            badges.add("purges");
        }
        if (consumersRequired() > 0)
        {
            badges.add("waits for " + consumersRequired() + " consumers");
        }
        if (disabled())
        {
            badges.add("disabled");
        }
        return badges;
    }

    private static boolean isTrue(Reading<Boolean> reading)
    {
        return reading.available() && reading.value();
    }

    /** One setting away from its default: its name, its value, and what a user sees because of it. */
    public record Effect(String setting, String value, String consequence)
    {
    }
}
