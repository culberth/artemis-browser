package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
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

    @BeforeEach
    void mocks()
    {
        queues = mock(QueueDirectory.class);
        addresses = mock(AddressDirectory.class);
        brokerInfo = mock(BrokerInfoService.class);
        browse = mock(QueueBrowseService.class);

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

    private StuckDiagnosisService service()
    {
        return new StuckDiagnosisService(queues, addresses, brokerInfo, browse);
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
        return new BrokerConsumer("c-1", queueName, "conn-1", browseOnly, 0, 0, 0, "OK", self);
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
