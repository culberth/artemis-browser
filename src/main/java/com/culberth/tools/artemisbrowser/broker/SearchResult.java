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

    /** The hits in one queue. */
    public record QueueMatches(String queueName, long matchCount, List<MessageSummary> messages)
    {

        public boolean partial()
        {
            return matchCount > messages.size();
        }
    }
}
