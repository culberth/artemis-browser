package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Searching is two-phase on purpose: count on every queue, browse only the ones that matched. A regression that browsed
 * everything would still return the right answer — it would just quietly pull every body on the broker through this
 * process to do it, so the cheap phase is what the tests hold in place.
 */
class MessageSearchServiceTest
{

    private BrokerSession brokerSession;
    private ManagementChannel management;
    private QueueDirectory queueDirectory;
    private QueueBrowseService browseService;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        queueDirectory = mock(QueueDirectory.class);
        browseService = mock(QueueBrowseService.class);
        given(brokerSession.requireManagement()).willReturn(management);
    }

    @Test
    @DisplayName("only the queues that counted a match are browsed")
    void browsesOnlyWhatMatched()
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue("orders", false), queue("payments", false), queue("DLQ", false)));
        counts("orders", 2);
        counts("payments", 0);
        counts("DLQ", 0);
        given(browseService.page("orders", "count = 1", 1, 2)).willReturn(page("orders", 2));

        SearchResult result = service().search("count = 1", false);

        assertEquals(3, result.queuesSearched());
        assertEquals(2, result.totalMatches());
        assertEquals(1, result.matches().size());
        assertEquals("orders", result.matches().get(0).queueName());
        verify(browseService, never()).page(eq("payments"), anyString(), anyInt(), anyInt());
        verify(browseService, never()).page(eq("DLQ"), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("internal queues are skipped unless they were asked for")
    void skipsInternalQueuesByDefault()
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue("orders", false), queue("$.artemis.internal.sf.cluster", true)));
        counts("orders", 0);

        assertEquals(1, service().search("count = 1", false).queuesSearched());
        verify(management, never()).invoke(ResourceNames.QUEUE + "$.artemis.internal.sf.cluster", "countMessages",
                "count = 1");
    }

    @Test
    @DisplayName("asking for internal queues searches them too")
    void searchesInternalQueuesWhenAsked()
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue("orders", false), queue("$.artemis.internal.sf.cluster", true)));
        counts("orders", 0);
        counts("$.artemis.internal.sf.cluster", 0);

        assertEquals(2, service().search("count = 1", true).queuesSearched());
    }

    @Test
    @DisplayName("more matches than the per-queue cap is reported as a partial result")
    void reportsATruncatedSearch()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("orders", false)));
        counts("orders", 500);
        given(browseService.page("orders", "count = 1", 1, 50)).willReturn(page("orders", 50));

        SearchResult result = service().search("count = 1", false);

        assertTrue(result.truncated());
        assertTrue(result.matches().get(0).partial());
        assertEquals(500, result.totalMatches());
    }

    @Test
    @DisplayName("a complete result is not flagged partial")
    void doesNotFlagACompleteResult()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("orders", false)));
        counts("orders", 3);
        given(browseService.page("orders", "count = 1", 1, 3)).willReturn(page("orders", 3));

        SearchResult result = service().search("count = 1", false);

        assertFalse(result.truncated());
        assertFalse(result.matches().get(0).partial());
    }

    @Test
    @DisplayName("the filter is trimmed, and an empty one is refused rather than matching everything")
    void refusesAnEmptyFilter()
    {
        assertThrows(BrokerException.class, () -> service().search("   ", false));
        assertThrows(BrokerException.class, () -> service().search(null, false));

        given(queueDirectory.overview()).willReturn(List.of(queue("orders", false)));
        counts("orders", 0);

        assertEquals("count = 1", service().search("  count = 1  ", false).filter());
    }

    private MessageSearchService service()
    {
        return new MessageSearchService(brokerSession, queueDirectory, browseService, 50);
    }

    private void counts(String queueName, long matches)
    {
        given(management.invoke(ResourceNames.QUEUE + queueName, "countMessages", "count = 1")).willReturn(matches);
    }

    private MessagePage page(String queueName, int messages)
    {
        List<MessageSummary> summaries = java.util.stream.IntStream.range(0, messages)
                .mapToObj(i -> new MessageSummary(i + 1, "ID:" + i, String.valueOf(i), "Text", 0L, "", 4, true, false,
                        10, "CORE", "body", false))
                .toList();
        return new MessagePage(queueName, "count = 1", 1, messages, messages, summaries);
    }

    private QueueOverview queue(String name, boolean internal)
    {
        return new QueueOverview(name, name, "ANYCAST", 0, 0, 0, 0, 0, 0, true, false, internal);
    }
}
