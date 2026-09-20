package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * What a cross-queue search found.
 *
 * @param queuesSearched how many queues were asked — so "nothing found" can be told apart from "nothing was looked at"
 * @param matches        only queues that matched; queues with zero hits are left out entirely
 * @param truncated      true when some queue had more matches than were fetched
 */
public record SearchResult(String filter, int queuesSearched, long totalMatches, boolean truncated,
        List<QueueMatches> matches)
{

    /**
     * The hits in one queue.
     *
     * @param matchCount how many were found, which is a floor rather than a total when {@code partial} is set
     * @param partial    true when the per-queue limit was reached, so there are more than were looked at. Told by the
     *                   service rather than inferred from the count: a filtered count stops after the broker's
     *                   management-browse-page-size, so it cannot be compared against anything
     */
    public record QueueMatches(String queueName, long matchCount, boolean partial, List<MessageSummary> messages)
    {
    }
}
