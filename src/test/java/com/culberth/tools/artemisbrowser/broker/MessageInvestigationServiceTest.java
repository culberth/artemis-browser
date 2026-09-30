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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.Outcome;
import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.QueueCoverage;
import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.State;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * One message looked for in every state, with every queue and state it could not look at accounted for.
 *
 * <p>
 * The failure this guards against is a quiet one: a lookup that skipped a queue, or a state, and then said "not found"
 * as if it had looked. So most of these tests are about coverage, not hits.
 */
class MessageInvestigationServiceTest
{

    private static final String ID = "ID:0afa26a4-bce3-11f1-85d3-00155d348692";
    private static final String FILTER = "AMQUserID = '" + ID + "'";

    private QueueDirectory queueDirectory;
    private QueueBrowseService browseService;
    private InFlightService inFlightService;
    private TransactionService transactionService;

    @BeforeEach
    void mocks()
    {
        queueDirectory = mock(QueueDirectory.class);
        browseService = mock(QueueBrowseService.class);
        inFlightService = mock(InFlightService.class);
        transactionService = mock(TransactionService.class);
        given(browseService.matching(anyString(), anyString(), anyInt())).willReturn(List.of());
        given(inFlightService.limit()).willReturn(5000);
        given(inFlightService.locate(anyString(), anyLong(), anyString()))
                .willAnswer(call -> InFlightLookup.notInFlight(call.getArgument(0)));
        given(transactionService.detailLimit()).willReturn(100);
        given(transactionService.collect()).willReturn(TransactionFixtures.none());
    }

    @Test
    @DisplayName("each queue is read scheduled, then waiting, then in flight — the order a message moves in")
    void readsInLifecycleOrder()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("orders", 2, 3)));
        given(browseService.scheduled("orders")).willReturn(List.of(scheduled("ID:other")));

        service().investigate(ID, false);

        InOrder order = inOrder(browseService, inFlightService, transactionService);
        order.verify(browseService).scheduled("orders");
        order.verify(browseService).matching("orders", FILTER, MessageInvestigationService.WAITING_PER_QUEUE);
        order.verify(inFlightService).locate("orders", 3, ID);
        order.verify(transactionService).collect();
    }

    @Test
    @DisplayName("a message is found in every state it is in, each hit labelled and timed")
    void findsEveryState()
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue("delayed", 1, 0), queue("orders", 0, 0), queue("work", 0, 2)));
        given(browseService.scheduled("delayed")).willReturn(List.of(scheduled(ID)));
        given(browseService.matching("orders", FILTER, 5)).willReturn(List.of(summary(ID)));
        InFlightMessage held = new InFlightMessage(ID, 9L, "Text", 4, true, 1L, "", "", Map.of());
        given(inFlightService.locate("work", 2, ID)).willReturn(
                new InFlightLookup("work", true, new InFlightConsumer("", "c", "s", "0", List.of(held)), held));
        PreparedTransaction branch = TransactionFixtures.branch("orders", "invoices", Instant.now());
        String received = branch.messages().get(2).userId();
        given(transactionService.collect()).willReturn(transactions(branch));

        MessageInvestigation byId = service().investigate(ID, false);
        MessageInvestigation byReceived = service().investigate(received, false);

        assertEquals(List.of(State.SCHEDULED, State.WAITING, State.IN_FLIGHT),
                byId.hits().stream().map(MessageInvestigation.Hit::state).toList());
        assertEquals(List.of("delayed", "orders", "work"),
                byId.hits().stream().map(MessageInvestigation.Hit::queueName).toList());
        assertTrue(byId.hits().stream().allMatch(hit -> hit.seenAt() != null));
        assertTrue(byId.complete());

        assertEquals(1, byReceived.hits().size());
        assertEquals(State.PREPARED_RECEIVE, byReceived.hits().get(0).state());
        assertEquals("orders", byReceived.hits().get(0).address());
        assertEquals(null, byReceived.hits().get(0).queueName(), "a prepared branch names no queue");
    }

    @Test
    @DisplayName("a message sent in a prepared branch is found on no queue, by the address it will go to")
    void findsAPreparedSend()
    {
        given(queueDirectory.overview()).willReturn(List.of());
        PreparedTransaction branch = TransactionFixtures.branch("orders", "invoices", Instant.now());
        given(transactionService.collect()).willReturn(transactions(branch));

        MessageInvestigation result = service().investigate(branch.messages().get(0).userId(), false);

        assertEquals(State.PREPARED_SEND, result.hits().get(0).state());
        assertEquals("invoices", result.hits().get(0).address());
    }

    @Test
    @DisplayName("nothing scheduled and nothing in flight is not read, and counts as covered")
    void doesNotReadEmptyStates()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("orders", 0, 0)));

        MessageInvestigation result = service().investigate(ID, false);

        verify(browseService, never()).scheduled(anyString());
        verify(inFlightService, never()).locate(anyString(), anyLong(), anyString());
        QueueCoverage orders = result.coverage().get(0);
        assertEquals(Outcome.NOTHING_THERE, orders.scheduled().outcome());
        assertEquals(Outcome.CHECKED, orders.waiting().outcome());
        assertEquals(Outcome.NOTHING_THERE, orders.inFlight().outcome());
        assertTrue(result.complete());
        assertFalse(result.found());
    }

    @Test
    @DisplayName("a queue over a per-queue limit is skipped before the call, with the limit named")
    void skipsOverPerQueueLimits()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("hoard", 6000, 9000)));

        MessageInvestigation result = service().investigate(ID, false);

        verify(browseService, never()).scheduled(anyString());
        verify(inFlightService, never()).locate(anyString(), anyLong(), anyString());
        QueueCoverage hoard = result.coverage().get(0);
        assertEquals(Outcome.SKIPPED, hoard.scheduled().outcome());
        assertTrue(hoard.scheduled().detail().contains("artemis.investigate.max-scheduled-per-queue"),
                hoard.scheduled().detail());
        assertEquals(Outcome.SKIPPED, hoard.inFlight().outcome());
        assertTrue(hoard.inFlight().detail().contains("9000 in flight"), hoard.inFlight().detail());
        assertTrue(hoard.inFlight().detail().contains("artemis.in-flight-limit"), hoard.inFlight().detail());
        assertFalse(result.complete());
        assertEquals(List.of(hoard), result.incomplete());
    }

    @Test
    @DisplayName("the whole lookup has an in-flight and a scheduled budget, and a queue that would pass it is skipped")
    void keepsToTheRequestBudget()
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue("a", 3000, 4000), queue("b", 3000, 4000), queue("c", 10, 10)));
        given(browseService.scheduled(anyString())).willReturn(List.of());

        MessageInvestigation result = new MessageInvestigationService(queueDirectory, browseService, inFlightService,
                transactionService, 2000, 100, 5000, 5000, 5000).investigate(ID, false);

        verify(inFlightService).locate("a", 4000, ID);
        verify(inFlightService, never()).locate(eq("b"), anyLong(), anyString());
        verify(inFlightService).locate("c", 10, ID);
        assertEquals(Outcome.SKIPPED, result.coverage().get(1).inFlight().outcome());
        assertTrue(result.coverage().get(1).inFlight().detail().contains("artemis.investigate.max-in-flight"));
        assertEquals(Outcome.SKIPPED, result.coverage().get(1).scheduled().outcome());
        assertTrue(result.coverage().get(1).scheduled().detail().contains("artemis.investigate.max-scheduled)"));
        assertEquals(4010, result.inFlightRead());
    }

    @Test
    @DisplayName("a refused read is reported as refused, and the other queues and states are still looked at")
    void reportsARefusalAndCarriesOn()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("secret", 1, 0), queue("orders", 0, 0)));
        given(browseService.matching("secret", FILTER, 5))
                .willThrow(new ManagementRefusal(Availability.DENIED, "AMQ229032: no permission='VIEW'"));
        given(browseService.scheduled("secret")).willThrow(new BrokerException("timed out"));
        given(browseService.matching("orders", FILTER, 5)).willReturn(List.of(summary(ID)));

        MessageInvestigation result = service().investigate(ID, false);

        QueueCoverage secret = result.coverage().get(0);
        assertEquals(Outcome.DENIED, secret.waiting().outcome());
        assertTrue(secret.waiting().detail().contains("AMQ229032"));
        assertEquals(Outcome.FAILED, secret.scheduled().outcome());
        assertEquals(1, result.hits().size());
        assertFalse(result.complete());
    }

    @Test
    @DisplayName("a delivering list cut at the limit is partly checked, not a clean no")
    void aTruncatedDeliveringListIsPartial()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("busy", 0, 4000)));
        given(inFlightService.locate("busy", 4000, ID)).willReturn(InFlightLookup.notChecked("busy"));

        MessageInvestigation result = service().investigate(ID, false);

        assertEquals(Outcome.PARTIAL, result.coverage().get(0).inFlight().outcome());
        assertFalse(result.complete());
    }

    @Test
    @DisplayName("once the result limit fills, later queues and the transactions are marked not reached")
    void stopsAtTheResultLimit()
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue("a", 0, 0), queue("b", 0, 0), queue("c", 0, 0), queue("d", 0, 0)));
        given(browseService.matching(anyString(), eq(FILTER), anyInt())).willReturn(List.of(summary(ID)));

        MessageInvestigation result = new MessageInvestigationService(queueDirectory, browseService, inFlightService,
                transactionService, 2000, 2, 20000, 5000, 20000).investigate(ID, false);

        assertEquals(2, result.hits().size());
        assertTrue(result.hitLimitReached());
        assertEquals(Outcome.NOT_REACHED, result.coverage().get(2).waiting().outcome());
        assertEquals(Outcome.NOT_REACHED, result.prepared().outcome());
        verify(browseService, never()).matching(eq("c"), anyString(), anyInt());
        verify(transactionService, never()).collect();
        assertFalse(result.complete());
    }

    @Test
    @DisplayName("queues past the queue limit are not looked at, and are listed as not reached")
    void stopsAtTheQueueLimit()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("a", 0, 0), queue("b", 0, 0)));

        MessageInvestigation result = new MessageInvestigationService(queueDirectory, browseService, inFlightService,
                transactionService, 1, 100, 20000, 5000, 20000).investigate(ID, false);

        assertEquals(Outcome.NOT_REACHED, result.coverage().get(1).waiting().outcome());
        assertTrue(result.coverage().get(1).waiting().detail().contains("artemis.investigate.max-queues"));
        verify(browseService, never()).matching(eq("b"), anyString(), anyInt());
    }

    @Test
    @DisplayName("internal queues are counted as left out unless asked for")
    void leavesOutInternalQueues()
    {
        QueueOverview internal = new QueueOverview("$sys", "$sys", "ANYCAST", 0, 0, 0, 0, 0, 0, true, false, true);
        given(queueDirectory.overview()).willReturn(List.of(internal, queue("orders", 0, 0)));

        assertEquals(1, service().investigate(ID, false).internalExcluded());
        assertEquals(1, service().investigate(ID, false).coverage().size());
        assertEquals(2, service().investigate(ID, true).coverage().size());
    }

    @Test
    @DisplayName("prepared branches over the detail limit, cut short, or refused are not a clean check")
    void preparedCoverage()
    {
        given(queueDirectory.overview()).willReturn(List.of());

        given(transactionService.collect()).willReturn(TransactionFixtures.denied());
        assertEquals(Outcome.DENIED, service().investigate(ID, false).prepared().outcome());

        given(transactionService.collect()).willReturn(new Transactions(Reading.of(List.of()), 150, "150 branches",
                Reading.of(List.of()), Reading.of(List.of()), "", Instant.now()));
        MessageInvestigation overLimit = service().investigate(ID, false);
        assertEquals(Outcome.SKIPPED, overLimit.prepared().outcome());
        assertTrue(overLimit.prepared().detail().contains("artemis.transactions.detail-limit"));

        PreparedTransaction branch = TransactionFixtures.branch("orders", "invoices", Instant.now());
        PreparedTransaction cut = new PreparedTransaction(branch.xid(), branch.formatId(), branch.globalId(),
                branch.branch(), branch.createdText(), branch.created(), "", branch.messages(), 5000, true);
        given(transactionService.collect()).willReturn(transactions(cut));
        assertEquals(Outcome.PARTIAL, service().investigate(ID, false).prepared().outcome());

        given(transactionService.collect()).willReturn(transactions(branch));
        assertEquals(Outcome.CHECKED, service().investigate(ID, false).prepared().outcome());
    }

    @Test
    @DisplayName("an ID that could break out of the filter literal is refused")
    void refusesAQuote()
    {
        assertThrows(BrokerException.class, () -> service().investigate("ID:x' OR '1'='1", false));
        verify(queueDirectory, never()).overview();
    }

    private MessageInvestigationService service()
    {
        return new MessageInvestigationService(queueDirectory, browseService, inFlightService, transactionService, 2000,
                100, 20000, 5000, 20000);
    }

    private static Transactions transactions(PreparedTransaction branch)
    {
        return new Transactions(Reading.of(List.of(branch)), 1, "", Reading.of(List.of()), Reading.of(List.of()), "",
                Instant.now());
    }

    private static QueueOverview queue(String name, long scheduled, long delivering)
    {
        return new QueueOverview(name, name, "ANYCAST", scheduled + delivering, delivering, scheduled, 0, 0, 0, true,
                false, false);
    }

    private static ScheduledMessage scheduled(String id)
    {
        return new ScheduledMessage(id, 1L, "Text", 4, true, "", "2026-09-30 12:00:00", false, Map.of());
    }

    private static MessageSummary summary(String id)
    {
        return new MessageSummary(1, id, "1", "Text", 0L, "", 4, true, false, 10, "CORE", false, Map.of(), "body",
                false);
    }
}
