package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each case here is a way a queue stops moving while looking fine from some other angle, which is the only reason the
 * page exists — every one of these facts was already on some other screen.
 */
class StuckDiagnosisServiceTest
{

    private QueueDirectory queues;
    private AddressDirectory addresses;
    private BrokerInfoService brokerInfo;
    private QueueBrowseService browse;
    private DivertDirectory diverts;
    private InFlightService inFlight;

    @BeforeEach
    void mocks()
    {
        queues = mock(QueueDirectory.class);
        addresses = mock(AddressDirectory.class);
        brokerInfo = mock(BrokerInfoService.class);
        browse = mock(QueueBrowseService.class);
        diverts = mock(DivertDirectory.class);
        given(diverts.all()).willReturn(List.of());
        inFlight = mock(InFlightService.class);
        given(inFlight.limit()).willReturn(5000);
        given(inFlight.oldest(anyString(), anyLong()))
                .willAnswer(call -> InFlightLookup.notInFlight(call.getArgument(0)));

        given(queues.overview()).willReturn(List.of());
        given(addresses.overview()).willReturn(List.of());
        given(brokerInfo.consumers()).willReturn(List.of());
        // 10% used against a 90% limit: comfortably clear of diskPressure(), which trips at 80% of
        // the limit rather than at it.
        given(brokerInfo.health()).willReturn(health(10, 90));
    }

    @Test
    @DisplayName("a healthy broker produces nothing to report")
    void saysNothingWhenNothingIsWrong()
    {
        given(queues.overview()).willReturn(List.of(queue("orders", 5, 0, 2, 100)));

        assertTrue(service().diagnose(false).isEmpty());
    }

    @Test
    @DisplayName("messages with no consumer is the common one")
    void flagsAQueueWithNoConsumer()
    {
        given(queues.overview()).willReturn(List.of(queue("orders", 7, 0, 0, 0)));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.isStuck());
        assertTrue(finding.title().contains("Nothing is reading 'orders'"), finding.title());
        assertEquals("orders", finding.queue());
    }

    @Test
    @DisplayName("a queue whose only consumers are browsers has consumers and still cannot drain")
    void flagsBrowseOnlyConsumers()
    {
        given(queues.overview()).willReturn(List.of(queue("orders", 7, 0, 1, 0)));
        given(brokerInfo.consumers()).willReturn(List.of(consumer("orders", true, false)));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.isStuck());
        assertTrue(finding.title().contains("Only browsers"), finding.title());
    }

    @Test
    @DisplayName("this tool's own browser does not count as the thing blocking the queue")
    void ignoresItsOwnBrowser()
    {
        given(queues.overview()).willReturn(List.of(queue("orders", 7, 0, 2, 50)));
        given(brokerInfo.consumers())
                .willReturn(List.of(consumer("orders", true, true), consumer("orders", false, false)));

        assertTrue(service().diagnose(false).isEmpty());
    }

    @Test
    @DisplayName("a paused queue is reported as paused, not as having no consumer")
    void flagsAPausedQueue()
    {
        given(queues.overview()).willReturn(
                List.of(new QueueOverview("orders", "orders", "ANYCAST", 3, 0, 0, 0, 3, 0, true, true, false)));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.title().contains("is paused"), finding.title());
    }

    @Test
    @DisplayName("a consumer that takes messages and never acknowledges is worth a look")
    void flagsDeliveredButNeverAcknowledged()
    {
        given(queues.overview()).willReturn(List.of(queue("orders", 5, 3, 1, 0)));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.title().contains("delivered nothing"), finding.title());
    }

    @Test
    @DisplayName("an overdue scheduled message is not moving; one that is merely waiting is fine")
    void tellsOverdueFromPending()
    {
        given(queues.overview()).willReturn(
                List.of(new QueueOverview("later", "later", "ANYCAST", 2, 0, 2, 1, 2, 1, true, false, false)));
        given(browse.scheduled("later")).willReturn(List.of(scheduled(true), scheduled(false)));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.isStuck());
        assertTrue(finding.title().contains("1 overdue"), finding.title());
    }

    @Test
    @DisplayName("unrouted messages at an address are gone, and nothing else reports them")
    void flagsUnroutedMessages()
    {
        given(addresses.overview()).willReturn(List.of(new AddressOverview("events", "MULTICAST", 0, 0, 10, 4, false,
                false, false,
                List.of(new QueueOverview("sub", "events", "MULTICAST", 0, 0, 0, 1, 0, 0, true, false, false)))));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.isStuck());
        assertTrue(finding.title().contains("dropped 4"), finding.title());
        assertEquals("events", finding.address());
    }

    @Test
    @DisplayName("the broker's own notification address is not reported as dropping messages")
    void ignoresTheBrokersOwnAddresses()
    {
        // activemq.notifications has no subscribers unless something asks for them, so its unrouted
        // count climbs on every broker from startup. A live broker two minutes old had 18.
        given(addresses.overview()).willReturn(List.of(new AddressOverview("activemq.notifications", "MULTICAST", 0, 0,
                0, 18, false, false, false, List.of())));

        assertTrue(service().diagnose(false).isEmpty());
        assertEquals(1, service().diagnose(true).size(), "asking for internals should still show it");
    }

    @Test
    @DisplayName("a broker near its disk limit outranks whatever a queue is doing")
    void putsBrokerPressureFirst()
    {
        given(brokerInfo.health()).willReturn(health(85, 90));
        given(queues.overview()).willReturn(List.of(queue("orders", 1, 0, 0, 0)));

        List<Finding> findings = service().diagnose(false);

        assertEquals(2, findings.size());
        assertTrue(findings.get(0).title().contains("disk limit"), findings.get(0).title());
    }

    @Test
    @DisplayName("internal queues are left out unless asked for")
    void skipsInternalQueues()
    {
        given(queues.overview()).willReturn(List
                .of(new QueueOverview("$.artemis.internal.sf", "x", "ANYCAST", 9, 0, 0, 0, 9, 0, true, false, true)));

        assertTrue(service().diagnose(false).isEmpty());
        assertEquals(1, service().diagnose(true).size());
    }

    @Test
    @DisplayName("no message body is read, so the page costs the same on a huge queue")
    void neverBrowsesBodies()
    {
        given(queues.overview()).willReturn(List.of(queue("huge", 500000, 0, 0, 0)));

        service().diagnose(false);

        verify(browse, never()).page(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("a durable subscription with nobody attached says what it costs, and links queue and address")
    void flagsAnAbandonedDurableSubscription()
    {
        given(queues.overview()).willReturn(
                List.of(new QueueOverview("app-b.audit", "events", "MULTICAST", 6, 0, 0, 0, 6, 0, true, false, false)));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.isStuck());
        assertTrue(finding.title().contains("Durable subscription 'app-b.audit'"), finding.title());
        assertTrue(finding.detail().contains("only grows"), finding.detail());
        assertEquals("app-b.audit", finding.queue());
        assertEquals("events", finding.address());
    }

    @Test
    @DisplayName("a multicast queue named after its own address is not treated as someone's subscription")
    void leavesBrokerConfiguredMulticastQueuesToTheGeneralFinding()
    {
        given(queues.overview()).willReturn(
                List.of(new QueueOverview("events", "events", "MULTICAST", 6, 0, 0, 0, 6, 0, true, false, false)));

        assertTrue(only(service().diagnose(false)).title().contains("Nothing is reading 'events'"));
    }

    @Test
    @DisplayName("an exclusive divert on an address with subscribers is worth a look; a copying one is not")
    void flagsExclusiveDivertsOnSubscribedAddresses()
    {
        QueueOverview subscriber = new QueueOverview("app.alerts", "alerts", "MULTICAST", 0, 0, 0, 1, 0, 0, true, false,
                false);
        given(addresses.overview()).willReturn(List
                .of(new AddressOverview("alerts", "MULTICAST", 0, 0, 0, 0, false, false, false, List.of(subscriber))));
        given(diverts.all()).willReturn(
                List.of(new Divert("alerts-archive", "alerts", "alerts.archive", "severity = 'high'", true, "PASS", ""),
                        new Divert("alerts-copy", "alerts", "alerts.audit", "", false, "PASS", "")));

        Finding finding = only(service().diagnose(false));

        assertEquals(Finding.WATCH, finding.severity());
        assertTrue(finding.title().contains("'alerts-archive' takes every message matching severity = 'high'"),
                finding.title());
        assertEquals("alerts", finding.address());
    }

    @Test
    @DisplayName("an exclusive divert on an address nobody subscribes to is only an entry point")
    void ignoresExclusiveDivertsWithNobodyToMiss()
    {
        given(addresses.overview()).willReturn(
                List.of(new AddressOverview("inbound", "ANYCAST", 0, 0, 0, 0, false, true, false, List.of())));
        given(diverts.all()).willReturn(List.of(new Divert("route", "inbound", "orders", "", true, "ANYCAST", "")));

        assertTrue(service().diagnose(false).isEmpty());
    }

    @Test
    @DisplayName("one consumer holding everything in flight while the others hold nothing is named")
    void flagsAHoardingConsumer()
    {
        given(queues.overview()).willReturn(List.of(queue("work", 250, 250, 2, 0)));
        BrokerConsumer hoarder = new BrokerConsumer("0", "work", "conn-a", "sess-a", "7", false, 250, 250, 0, "OK",
                false);
        given(brokerInfo.consumers()).willReturn(
                List.of(hoarder, new BrokerConsumer("0", "work", "conn-b", "sess-b", "8", false, 0, 0, 0, "OK", false),
                        // A browser is not one of "the others".
                        new BrokerConsumer("1", "work", "conn-c", "sess-c", "9", true, 0, 0, 0, "OK", false)));
        given(brokerInfo.consumersOn(anySet())).willReturn(List.of(new SubscriberConsumer("7", "work",
                "billing-worker-a", "artemis", "172.17.0.1:35238", "CORE", "", 250, 0)));

        Finding finding = service().diagnose(false).stream()
                .filter(candidate -> candidate.title().startsWith("One consumer holds")).findFirst().orElseThrow();

        assertEquals("work", finding.queue());
        assertTrue(finding.detail().contains("Client 'billing-worker-a' from 172.17.0.1:35238 holds 250"),
                finding.detail());
        assertTrue(finding.detail().contains("the other 1 consumer(s)"), finding.detail());
    }

    @Test
    @DisplayName("a consumer working one message while the others idle, a lone consumer, or a shared load is not hoarding")
    void doesNotFlagOrdinaryInFlight()
    {
        given(queues.overview()).willReturn(
                List.of(queue("busy", 5, 1, 2, 40), queue("alone", 50, 50, 1, 40), queue("shared", 50, 50, 2, 40)));
        given(brokerInfo.consumers())
                .willReturn(List.of(new BrokerConsumer("0", "busy", "a", "s", "1", false, 1, 1, 0, "OK", false),
                        new BrokerConsumer("0", "busy", "b", "s", "2", false, 0, 0, 0, "OK", false),
                        new BrokerConsumer("0", "alone", "a", "s", "3", false, 50, 50, 0, "OK", false),
                        new BrokerConsumer("0", "shared", "a", "s", "4", false, 25, 25, 0, "OK", false),
                        new BrokerConsumer("0", "shared", "b", "s", "5", false, 25, 25, 0, "OK", false)));

        assertTrue(service().diagnose(false).stream().noneMatch(f -> f.title().startsWith("One consumer holds")));
        verify(brokerInfo, never()).consumersOn(anySet());
    }

    @Test
    @DisplayName("a message sent long ago and still in flight is flagged, and the finding says the age is from sending")
    void flagsALongInFlightMessage()
    {
        given(queues.overview()).willReturn(List.of(queue("stuck", 1, 1, 1, 40), queue("backlog", 90, 1, 1, 40)));
        long sent = System.currentTimeMillis() - 42 * 60_000L;
        InFlightMessage old = new InFlightMessage("ID:old", 1, "Text", 4, true, sent, "", "", Map.of());
        InFlightConsumer holder = new InFlightConsumer("", "c", "s", "0", List.of(old),
                new SubscriberConsumer("7", "stuck", "slow-worker", "artemis", "", "CORE", "", 1, 0), 1L);
        given(inFlight.oldest("stuck", 1)).willReturn(new InFlightLookup("stuck", true, holder, old));
        given(inFlight.oldest("backlog", 1)).willReturn(new InFlightLookup("backlog", true, holder, old));

        List<Finding> findings = service().diagnose(false).stream()
                .filter(f -> f.title().contains("is still in flight")).toList();

        assertEquals(2, findings.size(), String.valueOf(findings));
        Finding stuck = findings.stream().filter(f -> "stuck".equals(f.queue())).findFirst().orElseThrow();
        assertTrue(stuck.title().contains("sent 42m"), stuck.title());
        assertTrue(stuck.detail().contains("ID:old is held, unacknowledged, by slow-worker (CORE)"), stuck.detail());
        assertTrue(stuck.detail().contains("not how long this consumer has held it"), stuck.detail());
        Finding backlog = findings.stream().filter(f -> "backlog".equals(f.queue())).findFirst().orElseThrow();
        assertTrue(backlog.detail().contains("89 message(s) waiting"), backlog.detail());
    }

    @Test
    @DisplayName("a message in flight for less than the threshold is a consumer working, not a finding")
    void doesNotFlagARecentInFlightMessage()
    {
        given(queues.overview()).willReturn(List.of(queue("work", 1, 1, 1, 40)));
        InFlightMessage recent = new InFlightMessage("ID:new", 1, "Text", 4, true, System.currentTimeMillis() - 5_000,
                "", "", Map.of());
        given(inFlight.oldest("work", 1)).willReturn(
                new InFlightLookup("work", true, new InFlightConsumer("", "c", "s", "0", List.of(recent)), recent));

        assertTrue(service().diagnose(false).isEmpty());
    }

    @Test
    @DisplayName("in-flight lists are read within a budget, smallest queue first, and what is left over is counted")
    void readsInFlightWithinABudget()
    {
        given(inFlight.limit()).willReturn(100);
        // Budget 4 x 100 = 400. Smallest first: 50 + 90 + 100 + 100 = 340 fit; the third 100 would make 440.
        // 900 is over the per-queue limit and is never asked for at all.
        given(queues.overview()).willReturn(
                List.of(queue("q900", 900, 900, 1, 1), queue("qa", 100, 100, 1, 1), queue("q50", 50, 50, 1, 1),
                        queue("qb", 100, 100, 1, 1), queue("q90", 90, 90, 1, 1), queue("qc", 100, 100, 1, 1)));

        Diagnosis result = service().run(false);

        verify(inFlight).oldest("q50", 50);
        verify(inFlight).oldest("q90", 90);
        verify(inFlight, never()).oldest("q900", 900);
        assertEquals(2, result.inFlightQueuesNotRead());
        assertEquals(1000, result.inFlightNotRead());
    }

    private StuckDiagnosisService service()
    {
        return new StuckDiagnosisService(queues, addresses, brokerInfo, browse, diverts, inFlight);
    }

    private Finding only(List<Finding> findings)
    {
        assertEquals(1, findings.size(), String.valueOf(findings));
        return findings.get(0);
    }

    private QueueOverview queue(String name, long messages, long delivering, int consumers, long acked)
    {
        return new QueueOverview(name, name, "ANYCAST", messages, delivering, 0, consumers, messages, acked, true,
                false, false);
    }

    private BrokerConsumer consumer(String queueName, boolean browseOnly, boolean self)
    {
        return new BrokerConsumer("c-1", queueName, "conn-1", "sess-1", "1", browseOnly, 0, 0, 0, "OK", self);
    }

    private ScheduledMessage scheduled(boolean overdue)
    {
        return new ScheduledMessage("ID:1", 1, "Text", 4, true, "", "2026-01-01 00:00:00", overdue, java.util.Map.of());
    }

    private BrokerHealth health(double diskPercent, long maxDiskPercent)
    {
        return new BrokerHealth("2.42.0", "1 day", "STARTED", "node", 1, 1, 1, 1024, 5, diskPercent, maxDiskPercent);
    }
}
