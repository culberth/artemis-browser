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
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Searching browses every queue rather than asking each one how many messages match.
 *
 * <p>
 * It used to do the cheap thing: {@code countMessages(filter)} per queue, then browse only the queues that reported a
 * match. Measuring at 100,000 messages showed why that was wrong — Artemis only examines the first
 * {@code management-browse-page-size} messages (200 by default) when counting with a filter, so a queue whose match sat
 * at position 99,999 reported zero and was never browsed. The search said "0 matches" for a message that was definitely
 * there, which is the exact failure the feature exists to prevent.
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
        given(browseService.matching(anyString(), anyString(), anyInt())).willReturn(List.of());
    }

    @Test
    @DisplayName("a match deep in a large queue is found, where counting would have missed it")
    void findsAMatchPastTheCountingWindow()
    {
        // The regression test for the measured bug: countMessages answers 0 for this queue.
        given(queueDirectory.overview()).willReturn(List.of(queue("orders", false), queue("payments", false)));
        given(browseService.matching("payments", "count = 99999", 50)).willReturn(messages(1));

        SearchResult result = service().search("count = 99999", false);

        assertEquals(1, result.totalMatches());
        assertEquals("payments", result.matches().get(0).queueName());
        verify(management, never()).invoke(anyString(), eq("countMessages"), anyString());
    }

    @Test
    @DisplayName("every queue is browsed, and only the ones that matched are reported")
    void browsesEveryQueueAndReportsTheMatches()
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue("orders", false), queue("payments", false), queue("DLQ", false)));
        given(browseService.matching("orders", "count = 1", 50)).willReturn(messages(2));

        SearchResult result = service().search("count = 1", false);

        assertEquals(3, result.queuesSearched());
        assertEquals(1, result.matches().size());
        assertEquals(2, result.totalMatches());
        verify(browseService).matching("payments", "count = 1", 50);
        verify(browseService).matching("DLQ", "count = 1", 50);
    }

    @Test
    @DisplayName("a queue that fills the per-queue limit reports a floor, not a total")
    void reportsAFloorWhenTheLimitIsReached()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("orders", false)));
        given(browseService.matching("orders", "count = 1", 50)).willReturn(messages(50));

        SearchResult result = service().search("count = 1", false);

        assertTrue(result.truncated());
        assertTrue(result.matches().get(0).partial());
        assertEquals(50, result.totalMatches(), "the number found, which is all that can be known cheaply");
    }

    @Test
    @DisplayName("a result inside the limit is complete and says so")
    void doesNotFlagACompleteResult()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("orders", false)));
        given(browseService.matching("orders", "count = 1", 50)).willReturn(messages(3));

        SearchResult result = service().search("count = 1", false);

        assertFalse(result.truncated());
        assertFalse(result.matches().get(0).partial());
    }

    @Test
    @DisplayName("internal queues are skipped unless they were asked for")
    void skipsInternalQueuesByDefault()
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue("orders", false), queue("$.artemis.internal.sf.cluster", true)));

        assertEquals(1, service().search("count = 1", false).queuesSearched());
        verify(browseService, never()).matching(eq("$.artemis.internal.sf.cluster"), anyString(), anyInt());

        assertEquals(2, service().search("count = 1", true).queuesSearched());
    }

    @Test
    @DisplayName("counting-only mode is for export, and still uses the cheap count")
    void countsOnlyForExport()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("orders", false)));
        given(management.invoke("queue.orders", "countMessages", "count = 1")).willReturn(7L);

        SearchResult result = service().counts("count = 1", false);

        assertEquals(7, result.totalMatches());
        assertTrue(result.matches().get(0).messages().isEmpty(), "counting mode fetches no messages");
        verify(browseService, never()).matching(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("the filter is trimmed, and an empty one is refused rather than matching everything")
    void refusesAnEmptyFilter()
    {
        assertThrows(BrokerException.class, () -> service().search("   ", false));
        assertThrows(BrokerException.class, () -> service().search(null, false));

        given(queueDirectory.overview()).willReturn(List.of(queue("orders", false)));

        assertEquals("count = 1", service().search("  count = 1  ", false).filter());
        verify(browseService).matching("orders", "count = 1", 50);
    }

    private MessageSearchService service()
    {
        return new MessageSearchService(brokerSession, queueDirectory, browseService, 50);
    }

    private List<MessageSummary> messages(int howMany)
    {
        return IntStream.range(0, howMany).mapToObj(i -> new MessageSummary(i + 1, "ID:" + i, String.valueOf(i), "Text",
                0L, "", 4, true, false, 10, "CORE", false, Map.of(), "body", false)).toList();
    }

    private QueueOverview queue(String name, boolean internal)
    {
        return new QueueOverview(name, name, "ANYCAST", 0, 0, 0, 0, 0, 0, true, false, internal);
    }
}
