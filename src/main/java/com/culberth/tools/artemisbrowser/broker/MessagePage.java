package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * One page of browsed messages.
 *
 * <p>
 * The total is known only without a filter. Artemis's filtered {@code countMessages} examines just the first
 * {@code management-browse-page-size} messages (200 by default) — measured on 2.44.0, 500 matches in a 1,000-message
 * queue counted as 100 while a filtered browse paged through all ten pages of 50 — so a filtered page carries no total,
 * only whether a next page exists, found by browsing for it.
 *
 * @param page          1-based page number
 * @param totalMatching messages {@code browse} can reach — waiting ones, not those in flight or scheduled — or
 *                      {@link #UNKNOWN} when filtered
 * @param more          a next page exists
 */
public record MessagePage(String queueName, String filter, int page, int pageSize, long totalMatching, boolean more,
        List<MessageSummary> messages)
{

    public static final long UNKNOWN = -1;

    /** A page whose total is known, so whether there is a next one follows from it. */
    public MessagePage(String queueName, String filter, int page, int pageSize, long totalMatching,
            List<MessageSummary> messages)
    {
        this(queueName, filter, page, pageSize, totalMatching,
                totalMatching >= 0 && (long) page * pageSize < totalMatching, messages);
    }

    public boolean totalKnown()
    {
        return totalMatching >= 0;
    }

    /** Pages there are, when the total is known; otherwise as many as have been seen, plus the next if there is one. */
    public long totalPages()
    {
        if (!totalKnown())
        {
            return page + (more ? 1 : 0);
        }
        if (totalMatching <= 0)
        {
            return 1;
        }
        return (totalMatching + pageSize - 1) / pageSize;
    }

    public boolean hasPrevious()
    {
        return page > 1;
    }

    public boolean hasNext()
    {
        return more;
    }

    public int previousPage()
    {
        return Math.max(1, page - 1);
    }

    public int nextPage()
    {
        return more ? page + 1 : page;
    }

    /** 1-based index of the first message on this page, for "showing 51-100 of 1200". */
    public long firstIndex()
    {
        return messages.isEmpty() ? 0 : (long) (page - 1) * pageSize + 1;
    }

    public long lastIndex()
    {
        return messages.isEmpty() ? 0 : firstIndex() + messages.size() - 1;
    }
}
