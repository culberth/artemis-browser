package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private RateService rates;
    private ConnectivityService connectivity;

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
        rates = mock(RateService.class);
        connectivity = mock(ConnectivityService.class);
        given(connectivity.collect(org.mockito.ArgumentMatchers.any())).willReturn(ConnectivityFixtures.standalone());
        given(rates.forDiagnosis(org.mockito.ArgumentMatchers.anyList()))
                .willReturn(new Diagnosis.Measured(Rates.none("not measured in this test"), false));
        given(inFlight.limit()).willReturn(5000);
        given(inFlight.oldest(anyString(), anyLong()))
                .willAnswer(call -> InFlightLookup.notInFlight(call.getArgument(0)));

        given(queues.overview()).willReturn(List.of());
        given(addresses.overview()).willReturn(List.of());
        given(addresses.blockedViaManagement(anyString())).willReturn(Reading.of(false));
        given(addresses.settings(anyString())).willReturn(AddressSettings.of(Map.of()));
        given(queues.groupCount(anyString())).willReturn(Reading.of(0L));
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
        assertFalse(finding.hasExplanation());
    }

    @Test
    @DisplayName("unrouted messages on an address whose queue purges are explained by the purge")
    void explainsUnroutedMessagesOnAPurgingQueue()
    {
        given(addresses.overview()).willReturn(List.of(new AddressOverview("it-q-purge", "ANYCAST", 0, 0, 3, 2, false,
                false, false,
                List.of(queue("it-q-purge", 0, 0, 0, 0).withBehavior(behavior("purgeOnNoConsumers", "true"))))));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.title().contains("dropped 2"), finding.title());
        assertTrue(finding.explanation().contains("purges when it has no consumer"), finding.explanation());
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
    @DisplayName("a disk figure the broker would not give is 'could not check', never a healthy disk")
    void saysWhatItCouldNotCheckOnTheBroker()
    {
        BrokerHealth full = health(95, 90);
        given(brokerInfo.health()).willReturn(new BrokerHealth(full.version(), full.uptime(),
                Reading.missing(Availability.DENIED, "mops.broker.getStatus"), full.nodeId(), full.connectionCount(),
                full.sessionCount(), full.consumerCount(), full.memoryUsedBytes(), full.memoryUsedPercent(),
                Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute diskStoreUsage"),
                full.maxDiskPercent(), full.collectedAt()));

        Diagnosis diagnosis = service().run(false);

        assertTrue(diagnosis.findings().isEmpty(), diagnosis.findings().toString());
        assertEquals(2, diagnosis.unchecked().size(), diagnosis.unchecked().toString());
        assertTrue(diagnosis.unchecked().get(0).startsWith("Disk use"), diagnosis.unchecked().toString());
        // An unread state is not "stopped".
        assertTrue(diagnosis.unchecked().get(1).contains("started"), diagnosis.unchecked().toString());
    }

    @Test
    @DisplayName("a refused consumer or address listing skips only the checks that need it")
    void carriesOnPastARefusedListing()
    {
        given(queues.overview()).willReturn(List.of(queue("orders", 4, 0, 0, 0)));
        given(brokerInfo.consumers()).willThrow(new ManagementRefusal(Availability.DENIED, "AMQ229032 consumers"));
        given(addresses.overview()).willThrow(new ManagementRefusal(Availability.DENIED, "AMQ229032 addresses"));

        Diagnosis diagnosis = service().run(false);

        assertEquals(1, diagnosis.findings().size(), diagnosis.findings().toString());
        assertTrue(diagnosis.findings().get(0).title().contains("Nothing is reading"),
                diagnosis.findings().get(0).title());
        assertEquals(2, diagnosis.unchecked().size(), diagnosis.unchecked().toString());
        assertTrue(diagnosis.unchecked().get(0).startsWith("Consumers"), diagnosis.unchecked().toString());
        assertTrue(diagnosis.unchecked().get(1).startsWith("Addresses"), diagnosis.unchecked().toString());
        assertTrue(diagnosis.unchecked().get(1).contains("AMQ229032 addresses"), diagnosis.unchecked().toString());
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
        assertEquals("billing-worker-a", finding.clientId(), "the finding links to the client it names");
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

    @Test
    @DisplayName("messages killed with no dead-letter address to go to were dropped, and that is reported")
    void flagsKilledWithNowhereToGo()
    {
        given(queues.overview()).willReturn(List.of(killedAndExpired("orders", 3, 0)));
        given(addresses.settings("orders")).willReturn(AddressSettings.of(Map.of("deadLetterAddress", "")));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.isStuck());
        assertEquals("'orders' has dropped 3 message(s) after too many delivery attempts", finding.title());
        assertTrue(finding.detail().contains("name no dead-letter address"), finding.detail());
        assertTrue(finding.detail().contains("if the settings were the same"), finding.detail());
    }

    @Test
    @DisplayName("a dead-letter address that does not exist, or has no queues, loses the message just the same")
    void flagsADeadLetterAddressThatCannotHoldAnything()
    {
        given(queues.overview()).willReturn(List.of(killedAndExpired("orders", 2, 0), killedAndExpired("audit", 1, 0)));
        given(addresses.settings("orders")).willReturn(AddressSettings.of(Map.of("deadLetterAddress", "GONE")));
        given(addresses.settings("audit")).willReturn(AddressSettings.of(Map.of("deadLetterAddress", "EMPTY")));
        given(addresses.overview()).willReturn(
                List.of(new AddressOverview("EMPTY", "ANYCAST", 0, 0, 0, 0, false, false, false, List.of())));

        List<Finding> findings = service().diagnose(false);

        assertTrue(findings.stream().anyMatch(f -> f.detail().contains("'GONE' does not exist")),
                String.valueOf(findings));
        assertTrue(findings.stream().anyMatch(f -> f.detail().contains("'EMPTY' has no queues")),
                String.valueOf(findings));
    }

    @Test
    @DisplayName("killed messages that went to a real dead-letter queue are not lost, and not a finding here")
    void doesNotFlagKilledThatWereKept()
    {
        given(queues.overview()).willReturn(List.of(killedAndExpired("orders", 3, 5)));
        given(addresses.settings("orders"))
                .willReturn(AddressSettings.of(Map.of("deadLetterAddress", "DLQ", "expiryAddress", "ExpiryQueue")));
        given(addresses.overview()).willReturn(List.of(
                new AddressOverview("DLQ", "ANYCAST", 3, 0, 3, 0, false, false, false,
                        List.of(queue("DLQ", 3, 0, 0, 0))),
                new AddressOverview("ExpiryQueue", "ANYCAST", 5, 0, 5, 0, false, false, false,
                        List.of(queue("ExpiryQueue", 5, 0, 0, 0)))));

        assertTrue(service().diagnose(false).isEmpty());
    }

    @Test
    @DisplayName("expired messages with no expiry address are worth a look, not an alarm: often meant to be dropped")
    void flagsExpiredWithNowhereToGoAsWatch()
    {
        given(queues.overview()).willReturn(List.of(killedAndExpired("prices", 0, 40)));
        given(addresses.settings("prices")).willReturn(AddressSettings.of(Map.of()));

        Finding finding = only(service().diagnose(false));

        assertFalse(finding.isStuck());
        assertEquals("'prices' has dropped 40 expired message(s)", finding.title());
    }

    @Test
    @DisplayName("a queue that has killed and expired nothing costs no settings read")
    void readsNoSettingsWhenNothingWasLost()
    {
        given(queues.overview()).willReturn(List.of(queue("orders", 5, 0, 2, 100)));

        service().diagnose(false);

        verify(addresses, never()).settings(anyString());
    }

    private QueueOverview killedAndExpired(String name, long killed, long expired)
    {
        return new QueueOverview(name, name, "ANYCAST", 0, 0, 0, 1, killed + expired, 0, true, false, false, expired,
                killed);
    }

    @Test
    @DisplayName("with a rate, an abandoned subscription is said to be still growing, or not")
    void saysWhetherAnAbandonedSubscriptionIsGrowing()
    {
        QueueOverview growing = new QueueOverview("app.audit", "events", "MULTICAST", 90, 0, 0, 0, 90, 0, true, false,
                false);
        QueueOverview idle = new QueueOverview("app.old", "events", "MULTICAST", 40, 0, 0, 0, 40, 0, true, false,
                false);
        given(queues.overview()).willReturn(List.of(growing, idle));
        given(rates.forDiagnosis(org.mockito.ArgumentMatchers.anyList())).willReturn(new Diagnosis.Measured(
                new Rates(Map.of("app.audit", new QueueRate(2.5, 0), "app.old", new QueueRate(0, 0)), 30_000, null),
                false));

        List<Finding> findings = service().diagnose(false);

        assertTrue(detailFor(findings, "app.audit").contains("still growing: 2.5 message(s)/s over the last 30s"));
        assertTrue(detailFor(findings, "app.old").contains("Nothing was added to it in the last 30s"));
    }

    @Test
    @DisplayName("with a rate, a long-in-flight message is told apart: queue still acknowledging, or stopped")
    void saysWhetherTheQueueIsMovingAroundALongInFlightMessage()
    {
        given(queues.overview()).willReturn(List.of(queue("busy", 1, 1, 1, 40), queue("stopped", 1, 1, 1, 40)));
        long sent = System.currentTimeMillis() - 42 * 60_000L;
        InFlightMessage old = new InFlightMessage("ID:old", 1, "Text", 4, true, sent, "", "", Map.of());
        InFlightConsumer holder = new InFlightConsumer("", "c", "s", "0", List.of(old));
        given(inFlight.oldest("busy", 1)).willReturn(new InFlightLookup("busy", true, holder, old));
        given(inFlight.oldest("stopped", 1)).willReturn(new InFlightLookup("stopped", true, holder, old));
        given(rates.forDiagnosis(org.mockito.ArgumentMatchers.anyList())).willReturn(new Diagnosis.Measured(
                new Rates(Map.of("busy", new QueueRate(3, 12), "stopped", new QueueRate(0, 0)), 15_000, null), true));

        Diagnosis result = service().run(false);

        assertTrue(detailFor(result.findings(), "busy").contains("acknowledged 12 message(s)/s over the last 15s"));
        assertTrue(detailFor(result.findings(), "stopped")
                .contains("Nothing on this queue was acknowledged in the last 15s"));
        assertTrue(result.measured().sampled());
    }

    @Test
    @DisplayName("an address an operator blocked is an observed finding, linked to the address")
    void flagsAManagementBlock()
    {
        given(addresses.overview()).willReturn(List.of(address("held", 0, 0, false, 0)));
        given(addresses.blockedViaManagement("held")).willReturn(Reading.of(true));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.isStuck());
        assertFalse(finding.isInferred(), "the broker reports a block itself");
        assertEquals("held", finding.address());
        verify(addresses, never()).settings(anyString());
    }

    @Test
    @DisplayName("each policy at its limit says what it does to a sender, inferred from usage and policy")
    void flagsEachHarmfulPolicyAtItsLimit()
    {
        // Shapes as recorded on 2.55.0 and 2.57.0 against a 20KB limit.
        given(addresses.overview()).willReturn(List.of(address("probe-block", 27, 418, false, 0),
                address("probe-fail", 7, 108, true, 0), address("probe-drop", 7, 108, true, 0)));
        given(addresses.settings("probe-block")).willReturn(settings("BLOCK"));
        given(addresses.settings("probe-fail")).willReturn(settings("FAIL"));
        given(addresses.settings("probe-drop")).willReturn(settings("DROP"));

        List<Finding> findings = service().diagnose(false);

        assertEquals(3, findings.size(), String.valueOf(findings));
        assertTrue(findings.stream().allMatch(f -> f.isStuck() && f.isInferred()), String.valueOf(findings));
        assertTrue(titleFor(findings, "probe-block").contains("producers are made to wait"));
        assertTrue(titleFor(findings, "probe-fail").contains("rejected"));
        assertTrue(titleFor(findings, "probe-drop").contains("discarded"));
    }

    @Test
    @DisplayName("paging under PAGE is the policy working, not a finding")
    void pagingIsNormal()
    {
        given(addresses.overview()).willReturn(List.of(address("probe-page", 40, 108, true, 9)));
        given(addresses.settings("probe-page")).willReturn(settings("PAGE"));

        assertTrue(service().diagnose(false).isEmpty());
    }

    @Test
    @DisplayName("near a limit that blocks is worth a look before it bites")
    void flagsNearALimit()
    {
        given(addresses.overview()).willReturn(List.of(address("busy", 10, 85, false, 0)));
        given(addresses.settings("busy")).willReturn(settings("BLOCK"));

        Finding finding = only(service().diagnose(false));

        assertFalse(finding.isStuck());
        assertTrue(finding.title().contains("near its limit"), finding.title());
    }

    @Test
    @DisplayName("settings are read only for addresses the listing already shows near a limit or paging")
    void readsSettingsOnlyWhenFlagged()
    {
        given(addresses.overview())
                .willReturn(List.of(address("quiet", 3, 10, false, 0), address("unlimited", 3, 0, false, 0)));

        assertTrue(service().diagnose(false).isEmpty());
        verify(addresses, never()).settings(anyString());
    }

    @Test
    @DisplayName("a policy that could not be read leaves the address flagged and says what was not checked")
    void saysWhenThePolicyCouldNotBeRead()
    {
        given(addresses.overview()).willReturn(List.of(address("probe-fail", 7, 108, true, 0)));
        given(addresses.settings("probe-fail")).willThrow(new ManagementRefusal(Availability.DENIED, "AMQ229032"));

        Diagnosis result = service().run(false);

        Finding finding = only(result.findings());
        assertFalse(finding.isStuck());
        assertTrue(result.unchecked().stream().anyMatch(line -> line.contains("probe-fail")),
                result.unchecked().toString());
    }

    @Test
    @DisplayName("a refused block read stops the scan once and says so, rather than asking every address")
    void stopsTheBlockScanAtARefusal()
    {
        given(addresses.overview()).willReturn(List.of(address("a", 0, 0, false, 0), address("b", 0, 0, false, 0)));
        given(addresses.blockedViaManagement(anyString()))
                .willReturn(Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute"));

        Diagnosis result = service().run(false);

        verify(addresses, org.mockito.Mockito.times(1)).blockedViaManagement(anyString());
        assertEquals(1, result.unchecked().stream().filter(line -> line.contains("blocked by an operator")).count());
    }

    @Test
    @DisplayName("global memory pressure names the largest addresses without blaming them")
    void namesTheLargestHoldersOfMemory()
    {
        given(brokerInfo.health())
                .willReturn(BrokerHealth.of("2.55.0", "1 day", "STARTED", "node", 1, 1, 1, 900_000_000L, 85, 10, 90));
        given(addresses.overview()).willReturn(List.of(address("small", 1, 0, false, 0), new AddressOverview("big",
                "ANYCAST", 1, 800_000_000L, 1, 0, false, false, false, List.of(queue("big", 1, 0, 1, 0)))));

        Finding finding = only(service().diagnose(false));

        assertEquals("big", finding.address());
        assertTrue(finding.detail().contains("'big' 762.9 MB"), finding.detail());
        assertTrue(finding.detail().contains("not necessarily why it filled"), finding.detail());
    }

    @Test
    @DisplayName("one consumer holding everything on an exclusive queue is the configuration, not a finding")
    void noHoardingOnAnExclusiveQueue()
    {
        given(queues.overview())
                .willReturn(List.of(queue("work", 20, 20, 2, 0).withBehavior(behavior("exclusive", "true"))));
        given(brokerInfo.consumers()).willReturn(List.of(holding("work", "c-1", 20), holding("work", "c-2", 0)));

        assertTrue(service().diagnose(false).stream().noneMatch(f -> f.title().contains("holds everything")));
    }

    @Test
    @DisplayName("on a queue with message groups, hoarding stays a finding with group affinity as the explanation")
    void explainsHoardingByGroups()
    {
        given(queues.overview()).willReturn(List.of(queue("work", 20, 20, 2, 5).withBehavior(behavior())));
        given(brokerInfo.consumers()).willReturn(List.of(holding("work", "c-1", 20), holding("work", "c-2", 0)));
        given(queues.groupCount("work")).willReturn(Reading.of(2L));

        Finding finding = service().diagnose(false).stream().filter(f -> f.title().contains("holds everything"))
                .findFirst().orElseThrow();
        assertTrue(finding.hasExplanation());
        assertTrue(finding.explanation().contains("group affinity"), finding.explanation());
        assertFalse(finding.detail().contains("group"), "the detail stays what was observed");
    }

    @Test
    @DisplayName("a queue waiting for more consumers says so, as observed counts plus the setting")
    void explainsADispatchGate()
    {
        given(queues.overview())
                .willReturn(List.of(queue("gated", 3, 0, 1, 0).withBehavior(behavior("consumersBeforeDispatch", "2"))));

        Finding finding = only(service().diagnose(false));

        assertFalse(finding.isStuck());
        assertTrue(finding.title().contains("waiting for 2 consumers"), finding.title());
        assertTrue(finding.detail().contains("1 of the 2"), finding.detail());
        assertTrue(finding.explanation().contains("consumers-before-dispatch 2"), finding.explanation());
    }

    @Test
    @DisplayName("nothing acknowledged on an address that makes queues non-destructive is explained, not excused")
    void explainsNothingAcknowledgedWhenNonDestructiveByDefault()
    {
        given(queues.overview()).willReturn(List.of(queue("browse-me", 3, 3, 1, 0).withBehavior(behavior())));
        given(addresses.settings("browse-me")).willReturn(AddressSettings.of(Map.of("defaultNonDestructive", "true")));

        Finding finding = only(service().diagnose(false));

        assertTrue(finding.title().contains("delivered nothing"), finding.title());
        assertTrue(finding.explanation().contains("non-destructive"), finding.explanation());
    }

    @Test
    @DisplayName("killed messages on a purging queue with nowhere to go are explained as possible purges")
    void explainsKilledMessagesOnAPurgingQueue()
    {
        given(queues.overview()).willReturn(
                List.of(killedAndExpired("purging", 3, 0).withBehavior(behavior("purgeOnNoConsumers", "true"))));
        given(addresses.overview()).willReturn(List.of(new AddressOverview("purging", "ANYCAST", 0, 0, 3, 0, false,
                false, false, List.of(queue("purging", 0, 0, 0, 0)))));
        given(addresses.settings("purging")).willReturn(AddressSettings.of(Map.of("deadLetterAddress", "")));

        Finding finding = only(service().diagnose(false));

        assertFalse(finding.isStuck());
        assertFalse(finding.title().contains("delivery attempts"), "a purge is not a failed delivery");
        assertTrue(finding.explanation().contains("purges when its last consumer leaves"), finding.explanation());
    }

    private static QueueBehavior behavior(String... overrides)
    {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>(
                java.util.Map.of("exclusive", "false", "lastValueKey", "", "ringSize", "-1", "groupBuckets", "-1",
                        "groupFirstKey", "", "consumersBeforeDispatch", "0", "delayBeforeDispatch", "-1",
                        "purgeOnNoConsumers", "false", "maxConsumers", "-1", "enabled", "true"));
        for (int i = 0; i < overrides.length; i += 2)
        {
            fields.put(overrides[i], overrides[i + 1]);
        }
        tools.jackson.databind.node.ObjectNode node = new tools.jackson.databind.ObjectMapper().createObjectNode();
        fields.forEach(node::put);
        return QueueBehavior.from(node);
    }

    private BrokerConsumer holding(String queueName, String id, long delivering)
    {
        return new BrokerConsumer(id, queueName, "conn-" + id, "sess-" + id, id, false, delivering, 0, 0, "OK", false);
    }

    private String titleFor(List<Finding> findings, String address)
    {
        return findings.stream().filter(f -> address.equals(f.address())).map(Finding::title).findFirst()
                .orElseThrow(() -> new AssertionError("no finding for " + address + " in " + findings));
    }

    private AddressOverview address(String name, long messages, long limitPercent, boolean paging, long pages)
    {
        return new AddressOverview(name, "ANYCAST", messages, 21679, messages, 0, paging, false, false,
                List.of(queue(name, messages, 0, 1, 0))).withStorage(limitPercent, pages);
    }

    private AddressSettings settings(String policy)
    {
        return AddressSettings.of(Map.of("addressFullMessagePolicy", policy, "maxSizeBytes", "20000", "pageSizeBytes",
                "10000", "pageLimitBytes", "-1", "pageLimitMessages", "-1"));
    }

    private String detailFor(List<Finding> findings, String queue)
    {
        return findings.stream().filter(f -> queue.equals(f.queue())).map(Finding::detail).findFirst()
                .orElseThrow(() -> new AssertionError("no finding for " + queue + " in " + findings));
    }

    private StuckDiagnosisService service()
    {
        return new StuckDiagnosisService(queues, addresses, brokerInfo, browse, diverts, inFlight, rates, connectivity);
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
        return BrokerHealth.of("2.42.0", "1 day", "STARTED", "node", 1, 1, 1, 1024, 5, diskPercent, maxDiskPercent);
    }
}
