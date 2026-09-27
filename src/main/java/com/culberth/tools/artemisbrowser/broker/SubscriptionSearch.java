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
 */
public record SubscriptionSearch(String filter, List<Row> rows)
{

    public long subscriptionsHolding()
    {
        return rows.stream().filter(Row::found).count();
    }

    /**
     * @param messages the matches fetched, up to the limit
     * @param partial  the limit was reached, so there may be more than {@code messages} shows
     */
    public record Row(Subscription subscription, List<MessageSummary> messages, boolean partial)
    {

        public boolean found()
        {
            return !messages.isEmpty();
        }

        /** Browse could not look at everything on this queue, so an empty result is not a clean "no". */
        public boolean unsearchable()
        {
            return subscription.deliveringCount() > 0 || subscription.scheduledCount() > 0;
        }

        public String verdict()
        {
            if (found())
            {
                return "waiting here — not yet delivered to a consumer";
            }
            StringBuilder unsearched = new StringBuilder();
            if (subscription.deliveringCount() > 0)
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
                return "not waiting — but " + unsearched + " could not be searched, and it may be among them";
            }
            return "not on this queue — consumed and acknowledged, turned away by the filter, or published before"
                    + " the subscription existed";
        }
    }
}
