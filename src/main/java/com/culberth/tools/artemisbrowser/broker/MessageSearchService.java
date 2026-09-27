package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Finds messages across every queue at once — the "I have the order ID but not the queue" case.
 *
 * <p>
 * Every queue is browsed with the filter, bounded to {@code artemis.search-max-per-queue} rows each. This used to be
 * two-phase and cheaper — {@code countMessages(filter)} per queue, then browse only the queues that reported a match —
 * until measuring at 100,000 messages showed the count cannot carry that weight. Artemis examines only the first
 * {@code management-browse-page-size} messages (200 by default) when counting with a filter, so a queue whose match
 * sits at position 99,999 reports zero and was never browsed: the search answered "nothing found" for a message that
 * was there. A filtered browse scans the whole queue and finds it.
 *
 * <p>
 * What that costs is a broker-side scan of each queue instead of a counter read, which is the price of the answer being
 * true. What it gives up is an exact total: the number reported per queue is how many were found, a floor, not how many
 * exist.
 *
 * <p>
 * Browse cannot see a message delivered to a consumer and not yet acknowledged, so every search reports how many such
 * messages it could not look at. An exact message-ID lookup ({@link MessageIdLookup}) then checks them after all, by
 * string comparison against each queue's delivering list — any other filter cannot be, without evaluating Artemis's
 * filter language here.
 *
 * <p>
 * Read-only throughout: counting and browsing both leave the queue untouched.
 */
@Service
public class MessageSearchService
{

    private final QueueDirectory queueDirectory;
    private final QueueBrowseService browseService;
    private final InFlightService inFlightService;
    private final int maxPerQueue;

    public MessageSearchService(QueueDirectory queueDirectory, QueueBrowseService browseService,
            InFlightService inFlightService, @Value("${artemis.search-max-per-queue:50}") int maxPerQueue)
    {
        this.queueDirectory = queueDirectory;
        this.browseService = browseService;
        this.inFlightService = inFlightService;
        this.maxPerQueue = maxPerQueue;
    }

    /**
     * @param filter          Artemis core filter syntax, the same dialect the single-queue filter box uses
     * @param includeInternal whether to search Artemis's own internal queues as well
     */
    public SearchResult search(String filter, boolean includeInternal)
    {
        return search(filter, includeInternal, maxPerQueue);
    }

    /**
     * Which queues match, without fetching their messages — one filtered browse of one row per queue, so each match
     * count is "at least one", not a total.
     *
     * <p>
     * Export uses this: it is going to fetch each matching queue's messages itself, with full bodies and its own limit,
     * so browsing previews here first would be work thrown away on every queue that matched.
     */
    public SearchResult counts(String filter, boolean includeInternal)
    {
        return search(filter, includeInternal, 0);
    }

    private SearchResult search(String filter, boolean includeInternal, int perQueue)
    {
        String effectiveFilter = MessageIdLookup.filterFor(filter);
        if (effectiveFilter.isEmpty())
        {
            throw new BrokerException("Enter a filter to search for.");
        }
        String messageId = MessageIdLookup.messageId(effectiveFilter);

        List<SearchResult.QueueMatches> matches = new ArrayList<>();
        List<InFlightLookup> inFlight = new ArrayList<>();
        long inFlightNotSearched = 0;
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

            if (perQueue <= 0)
            {
                // Which queues match, for export, which fetches each queue's messages itself. By a
                // one-row filtered browse, not a filtered count: the count samples the first 200
                // messages, so a queue whose matches all lie past them counted zero and was left
                // out of the export while the search page, which browses, listed it.
                if (!browseService.matching(queue.name(), effectiveFilter, 1).isEmpty())
                {
                    matches.add(new SearchResult.QueueMatches(queue.name(), 1, true, List.of()));
                    total++;
                }
                continue;
            }

            List<MessageSummary> found = browseService.matching(queue.name(), effectiveFilter, perQueue);
            if (found.isEmpty())
            {
                if (queue.deliveringCount() > 0)
                {
                    // Browse first, then the delivering list: a message delivered in between is
                    // caught by one or the other. One copy per queue, so a queue browse found it
                    // on needs no second look.
                    InFlightLookup lookup = messageId == null ? InFlightLookup.notChecked(queue.name())
                            : inFlightService.locate(queue.name(), queue.deliveringCount(), messageId);
                    if (lookup.found())
                    {
                        inFlight.add(lookup);
                    }
                    else if (!lookup.checked())
                    {
                        inFlightNotSearched += queue.deliveringCount();
                    }
                }
                continue;
            }
            // The number found, not the number there are: a filtered count stops after the broker's
            // management-browse-page-size, so it cannot be trusted as a total.
            total += found.size();
            boolean filledTheLimit = found.size() >= perQueue;
            truncated = truncated || filledTheLimit;
            matches.add(new SearchResult.QueueMatches(queue.name(), found.size(), filledTheLimit, found));
        }
        return new SearchResult(effectiveFilter, searched, total, truncated, matches, messageId, inFlight,
                inFlightNotSearched);
    }

}
