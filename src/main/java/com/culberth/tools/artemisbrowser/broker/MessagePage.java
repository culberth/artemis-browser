package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * One page of browsed messages.
 *
 * @param page          1-based page number
 * @param totalMatching total messages matching {@code filter} — the whole queue when there is none
 */
public record MessagePage(String queueName, String filter, int page, int pageSize, long totalMatching,
        List<MessageSummary> messages)
{

    public long totalPages()
    {
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
        return page < totalPages();
    }

    public int previousPage()
    {
        return Math.max(1, page - 1);
    }

    public int nextPage()
    {
        return (int) Math.min(totalPages(), (long) page + 1);
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
