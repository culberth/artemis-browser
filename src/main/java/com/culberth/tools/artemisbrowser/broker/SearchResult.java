package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * What a cross-queue search found.
 *
 * @param queuesSearched      how many queues were asked — so "nothing found" can be told apart from "nothing was looked
 *                            at"
 * @param matches             only queues that matched; queues with zero hits are left out entirely
 * @param truncated           true when some queue had more matches than were fetched
 * @param messageId           the ID looked up when the filter was an exact lookup ({@link MessageIdLookup}), else null
 * @param inFlight            where that message was found in flight — delivered to a consumer, not acknowledged, and so
 *                            invisible to the browse that found {@code matches}
 * @param inFlightNotSearched messages in flight on the searched queues that this search could not look at: all of them
 *                            for an ordinary filter, and for an ID lookup those on queues over
 *                            {@code artemis.in-flight-limit}
 */
public record SearchResult(String filter, int queuesSearched, long totalMatches, boolean truncated,
        List<QueueMatches> matches, String messageId, List<InFlightLookup> inFlight, long inFlightNotSearched)
{

    public SearchResult(String filter, int queuesSearched, long totalMatches, boolean truncated,
            List<QueueMatches> matches)
    {
        this(filter, queuesSearched, totalMatches, truncated, matches, null, List.of(), 0);
    }

    /** Nothing waiting and nothing in flight: the only case where the page may say nothing was found. */
    public boolean nothingFound()
    {
        return matches.isEmpty() && inFlight.isEmpty();
    }

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
