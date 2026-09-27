package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

    private QueueDirectory queueDirectory;
    private QueueBrowseService browseService;
    private InFlightService inFlightService;

    @BeforeEach
    void mocks()
    {
        queueDirectory = mock(QueueDirectory.class);
        browseService = mock(QueueBrowseService.class);
        inFlightService = mock(InFlightService.class);
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
    @DisplayName("export picks its queues by a one-row browse, not the filtered count that samples 200 messages")
    void countsOnlyForExport()
    {
        // Measured on 2.44.0: a filtered count of a queue whose matches all lie past its first 200
        // messages is 0, which left the queue out of the export while the search page listed it.
        given(queueDirectory.overview()).willReturn(List.of(queue("orders", false), queue("payments", false)));
        given(browseService.matching("orders", "count = 1", 1)).willReturn(messages(1));

        SearchResult result = service().counts("count = 1", false);

        assertEquals(1, result.matches().size());
        assertEquals("orders", result.matches().get(0).queueName());
        assertTrue(result.matches().get(0).partial(), "one row found is a floor, not a count");
        assertTrue(result.matches().get(0).messages().isEmpty(), "export fetches the messages itself");
        verify(browseService, never()).matching(anyString(), anyString(), eq(50));
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

    @Test
    @DisplayName("an ordinary search counts the in-flight messages it could not look at, and does not read them")
    void countsInFlightItCouldNotSearch()
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue("orders", false, 3), queue("payments", false, 4), queue("audit", false, 0)));
        given(browseService.matching("payments", "region = 'eu'", 50)).willReturn(messages(1));

        SearchResult result = service().search("region = 'eu'", false);

        // payments had a match, so its in-flight ones do not change the answer for it; orders might.
        assertEquals(3, result.inFlightNotSearched());
        assertTrue(result.inFlight().isEmpty());
        verifyNoInteractions(inFlightService);
    }

    @Test
    @DisplayName("a message-ID lookup finds the message in flight where browse could not")
    void findsAMessageInFlightById()
    {
        String id = "ID:3be27521-bac0-11f1-8802-00155d348692";
        given(queueDirectory.overview())
                .willReturn(List.of(queue("orders", false, 3), queue("hoard", false, 9000), queue("idle", false, 0)));
        InFlightLookup hit = new InFlightLookup("orders", true, new InFlightConsumer("", "c", "s", "0", List.of()),
                mock(InFlightMessage.class));
        given(inFlightService.locate("orders", 3, id)).willReturn(hit);
        given(inFlightService.locate("hoard", 9000, id)).willReturn(InFlightLookup.notChecked("hoard"));

        SearchResult result = service().search(id, false);

        assertEquals("AMQUserID = '" + id + "'", result.filter());
        assertEquals(List.of(hit), result.inFlight());
        assertEquals(9000, result.inFlightNotSearched(), "the queue over the limit was not checked");
        assertFalse(result.nothingFound());
        verify(inFlightService, never()).locate(eq("idle"), anyLong(), anyString());
    }

    private MessageSearchService service()
    {
        return new MessageSearchService(queueDirectory, browseService, inFlightService, 50);
    }

    private List<MessageSummary> messages(int howMany)
    {
        return IntStream.range(0, howMany).mapToObj(i -> new MessageSummary(i + 1, "ID:" + i, String.valueOf(i), "Text",
                0L, "", 4, true, false, 10, "CORE", false, Map.of(), "body", false)).toList();
    }

    private QueueOverview queue(String name, boolean internal)
    {
        return queue(name, internal, 0);
    }

    private QueueOverview queue(String name, boolean internal, long delivering)
    {
        return new QueueOverview(name, name, "ANYCAST", delivering, delivering, 0, 0, 0, 0, true, false, internal);
    }
}
