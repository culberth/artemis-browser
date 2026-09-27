package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * Which of an address's subscriptions still hold a message — "did subscriber B get it?" — answered per subscription,
 * including the ones where nothing was found, because on this question "not here" is half the answer.
 *
 * <p>
 * Found by filtered {@code browse}, which scans the whole queue; never by a filtered count, which samples only the
 * first {@code management-browse-page-size} messages. But browse cannot see two kinds of message a queue still holds:
 * scheduled ones, and ones delivered to a consumer and not yet acknowledged. So "not found" is only "not on this queue"
 * when the queue has neither — {@link Row#verdict()} says which case it is rather than rounding them together.
 *
 * <p>
 * A lookup by exact message ID closes the in-flight half of that gap: the delivering list is checked by string
 * comparison, so the answer becomes "in flight to consumer X" or a real "no". Any other filter leaves it open, since
 * checking in-flight messages against a filter would mean evaluating Artemis's filter language here.
 *
 * @param messageId the ID looked up when this was an exact lookup, else null
 */
public record SubscriptionSearch(String filter, String messageId, List<Row> rows)
{

    public SubscriptionSearch(String filter, List<Row> rows)
    {
        this(filter, null, rows);
    }

    public long subscriptionsHolding()
    {
        return rows.stream().filter(row -> row.found() || row.inFlightHere()).count();
    }

    /**
     * @param messages the matches fetched, up to the limit
     * @param partial  the limit was reached, so there may be more than {@code messages} shows
     * @param inFlight the check of the delivering list, for an exact ID lookup browse did not find; else null
     */
    public record Row(Subscription subscription, List<MessageSummary> messages, boolean partial,
            InFlightLookup inFlight)
    {

        public Row(Subscription subscription, List<MessageSummary> messages, boolean partial)
        {
            this(subscription, messages, partial, null);
        }

        public boolean found()
        {
            return !messages.isEmpty();
        }

        /** Held by a consumer on this subscription, delivered and not yet acknowledged. */
        public boolean inFlightHere()
        {
            return inFlight != null && inFlight.found();
        }

        /** Delivering messages this search could not look at: all of them, unless the lookup checked them. */
        private boolean inFlightUnsearched()
        {
            return subscription.deliveringCount() > 0 && (inFlight == null || !inFlight.checked());
        }

        /** Browse could not look at everything on this queue, so an empty result is not a clean "no". */
        public boolean unsearchable()
        {
            return inFlightUnsearched() || subscription.scheduledCount() > 0;
        }

        public String verdict()
        {
            if (found())
            {
                return "waiting here — not yet delivered to a consumer";
            }
            if (inFlightHere())
            {
                return "in flight to " + inFlight.holder().describe() + " — delivered, not yet acknowledged";
            }
            StringBuilder unsearched = new StringBuilder();
            if (inFlightUnsearched())
            {
                unsearched.append(subscription.deliveringCount()).append(" in flight to a consumer");
            }
            if (subscription.scheduledCount() > 0)
            {
                unsearched.append(unsearched.isEmpty() ? "" : " and ").append(subscription.scheduledCount())
                        .append(" scheduled");
            }
            if (!unsearched.isEmpty())
            {
                String why = inFlight != null && !inFlight.checked() && subscription.deliveringCount() > 0
                        ? " (more in flight than this tool will read)"
                        : "";
                return "not waiting — but " + unsearched + why + " could not be searched, and it may be among them";
            }
            return "not on this queue — consumed and acknowledged, turned away by the filter, or published before"
                    + " the subscription existed";
        }
    }
}
