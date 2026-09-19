package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Finds messages across every queue at once — the "I have the order ID but not the queue" case.
 *
 * <p>
 * Deliberately two-phase. {@code countMessages(filter)} is asked of every queue first, which is one cheap round trip
 * each and returns a number rather than message bodies; only the queues that actually matched are then browsed.
 * Browsing every queue speculatively would pull message bodies from the whole broker to answer a question that is
 * usually "it is in exactly one of these".
 *
 * <p>
 * Read-only throughout: counting and browsing both leave the queue untouched.
 */
@Service
public class MessageSearchService
{

    private final BrokerSession brokerSession;
    private final QueueDirectory queueDirectory;
    private final QueueBrowseService browseService;
    private final int maxPerQueue;

    public MessageSearchService(BrokerSession brokerSession, QueueDirectory queueDirectory,
            QueueBrowseService browseService, @Value("${artemis.search-max-per-queue:50}") int maxPerQueue)
    {
        this.brokerSession = brokerSession;
        this.queueDirectory = queueDirectory;
        this.browseService = browseService;
        this.maxPerQueue = maxPerQueue;
    }

    /**
     * @param filter          Artemis core filter syntax, the same dialect the single-queue filter box uses
     * @param includeInternal whether to search Artemis's own internal queues as well
     */
    public SearchResult search(String filter, boolean includeInternal)
    {
        String effectiveFilter = filter == null ? "" : filter.trim();
        if (effectiveFilter.isEmpty())
        {
            throw new BrokerException("Enter a filter to search for.");
        }

        ManagementChannel management = brokerSession.requireManagement();
        List<SearchResult.QueueMatches> matches = new ArrayList<>();
        long total = 0;
        int searched = 0;
        boolean truncated = false;

        for (QueueOverview queue : queueDirectory.overview())
        {
            if (queue.internalQueue() && !includeInternal)
            {
                continue;
            }
            searched++;

            long count = count(management, queue.name(), effectiveFilter);
            if (count <= 0)
            {
                continue;
            }
            total += count;

            int take = (int) Math.min(count, maxPerQueue);
            MessagePage page = browseService.page(queue.name(), effectiveFilter, 1, take);
            truncated = truncated || count > page.messages().size();
            matches.add(new SearchResult.QueueMatches(queue.name(), count, page.messages()));
        }
        return new SearchResult(effectiveFilter, searched, total, truncated, matches);
    }

    private long count(ManagementChannel management, String queueName, String filter)
    {
        Object result = management.invoke(ResourceNames.QUEUE + queueName, "countMessages", filter);
        return result instanceof Number number ? number.longValue() : 0L;
    }
}
