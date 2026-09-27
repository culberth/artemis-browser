package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How far behind each subscriber is, and which subscriptions still hold a message.
 *
 * <p>
 * Both answers have a way to be confidently wrong that this project has already met. Lag measured by
 * {@code messagesAdded} calls every filter lag. A search that says "not here" for a subscription whose messages are in
 * flight — invisible to browse, verified against a 2.44.0 broker — says the subscriber never got something it is
 * holding right now.
 */
class SubscriberLagTest
{

    @Test
    @DisplayName("ages read as two units at most")
    void formatsAges()
    {
        assertEquals("45s", AddressDetail.ageText(45_000));
        assertEquals("12m 3s", AddressDetail.ageText((12 * 60 + 3) * 1000L));
        assertEquals("4h 10m", AddressDetail.ageText((4 * 3600 + 10 * 60) * 1000L));
        assertEquals("3d 2h", AddressDetail.ageText((3 * 86400 + 2 * 3600) * 1000L));
        assertEquals("0s", AddressDetail.ageText(-5), "a clock slightly ahead of the broker is not negative lag");
    }

    @Test
    @DisplayName("furthest behind is decided by age, and only when there is something to compare")
    void furthestBehindByAge()
    {
        // The filtered subscription has added far fewer — its filter working — and is still the one behind.
        Subscription filtered = subscription("app.filtered", 1, 0, 0, 1);
        Subscription busy = subscription("app.busy", 900, 0, 0, 5000);

        AddressDetail detail = new AddressDetail(null, List.of(filtered, busy), List.of(), List.of(),
                Map.of("app.filtered", 3_600_000L, "app.busy", 20_000L));
        assertEquals("app.filtered", detail.furthestBehind());

        AddressDetail alone = new AddressDetail(null, List.of(busy), List.of(), List.of(), Map.of("app.busy", 20_000L));
        assertNull(alone.furthestBehind(), "one subscription with messages is not behind anything");
    }

    @Test
    @DisplayName("no badge when nobody is clearly behind — the same burst read a few milliseconds apart")
    void noBadgeForATie()
    {
        // What a real broker showed: three subscriptions all at "4m 40s", published in one burst.
        List<Subscription> three = List.of(subscription("a", 1, 0, 0, 1), subscription("b", 6, 0, 0, 6),
                subscription("c", 6, 0, 0, 6));
        AddressDetail burst = new AddressDetail(null, three, List.of(), List.of(),
                Map.of("a", 280_004L, "b", 280_011L, "c", 280_017L));
        assertNull(burst.furthestBehind(), "a 13ms lead is not being behind");

        // Both an hour behind, one by a few minutes more: behind, but not further behind than the other.
        AddressDetail close = new AddressDetail(null, three.subList(0, 2), List.of(), List.of(),
                Map.of("a", 3_600_000L, "b", 3_900_000L));
        assertNull(close.furthestBehind(), "twice the runner-up's age is the bar");
    }

    @Test
    @DisplayName("one subscription behind while the rest are caught up is marked")
    void badgeWhenTheOthersHaveNothingWaiting()
    {
        List<Subscription> two = List.of(subscription("stuck", 40, 0, 0, 40), subscription("caught-up", 0, 0, 0, 40));

        assertEquals("stuck",
                new AddressDetail(null, two, List.of(), List.of(), Map.of("stuck", 3_600_000L)).furthestBehind());
        assertNull(new AddressDetail(null, two, List.of(), List.of(), Map.of("stuck", 5_000L)).furthestBehind(),
                "five seconds is not behind, even against zero");
    }

    @Test
    @DisplayName("each search verdict says which kind of 'not found' it is")
    void verdictsSayWhatWasSearched()
    {
        SubscriptionSearch.Row found = row(subscription("a", 1, 0, 0, 1), 1);
        SubscriptionSearch.Row clean = row(subscription("b", 0, 0, 0, 1), 0);
        SubscriptionSearch.Row inFlight = row(subscription("c", 3, 3, 0, 3), 0);
        SubscriptionSearch.Row both = row(subscription("d", 5, 2, 1, 5), 0);

        assertTrue(found.verdict().startsWith("waiting here"));
        assertFalse(clean.unsearchable());
        assertTrue(clean.verdict().startsWith("not on this queue"), clean.verdict());
        assertTrue(inFlight.unsearchable());
        assertTrue(inFlight.verdict().contains("3 in flight"), inFlight.verdict());
        assertTrue(both.verdict().contains("2 in flight to a consumer and 1 scheduled"), both.verdict());
    }

    @Test
    @DisplayName("the search browses every subscription by its bare name and reports each, found or not")
    void searchesEverySubscription()
    {
        QueueBrowseService browse = mock(QueueBrowseService.class);
        given(browse.matching("app.one", "orderId = 'A'", AddressDetailService.FIND_LIMIT))
                .willReturn(List.of(mock(MessageSummary.class)));
        given(browse.matching("app.two", "orderId = 'A'", AddressDetailService.FIND_LIMIT)).willReturn(List.of());
        AddressDetailService service = new AddressDetailService(null, null, null, browse, null, null);
        AddressDetail detail = new AddressDetail(null,
                List.of(subscription("app.one", 1, 0, 0, 1), subscription("app.two", 0, 0, 0, 1)), List.of(),
                List.of());

        SubscriptionSearch result = service.find(detail, "  orderId = 'A' ");

        assertEquals(2, result.rows().size());
        assertEquals(1, result.subscriptionsHolding());
        assertEquals("orderId = 'A'", result.filter());
        assertThrows(BrokerException.class, () -> service.find(detail, " "));
    }

    @Test
    @DisplayName("an ID lookup browse did not find is checked in flight: a real yes, and a real no")
    void looksUpInFlightByMessageId()
    {
        String id = "ID:3be27521-bac0-11f1-8802-00155d348692";
        String filter = "AMQUserID = '" + id + "'";
        QueueBrowseService browse = mock(QueueBrowseService.class);
        given(browse.matching(anyString(), eq(filter), anyInt())).willReturn(List.of());
        given(browse.matching("app.waiting", filter, AddressDetailService.FIND_LIMIT))
                .willReturn(List.of(mock(MessageSummary.class)));
        InFlightService inFlight = mock(InFlightService.class);
        InFlightConsumer holder = new InFlightConsumer("", "c", "s", "0", List.of(),
                new SubscriberConsumer("7", "app.held", "billing-worker", "artemis", "10.0.0.7:5000", "CORE", "", 3, 0),
                3L);
        given(inFlight.locate("app.held", 3, id))
                .willReturn(new InFlightLookup("app.held", true, holder, mock(InFlightMessage.class)));
        given(inFlight.locate("app.acked", 2, id)).willReturn(InFlightLookup.notInFlight("app.acked"));
        given(inFlight.locate("app.hoard", 9000, id)).willReturn(InFlightLookup.notChecked("app.hoard"));
        AddressDetailService service = new AddressDetailService(null, null, null, browse, null, inFlight);
        AddressDetail detail = new AddressDetail(null,
                List.of(subscription("app.waiting", 1, 0, 0, 1), subscription("app.held", 3, 3, 0, 3),
                        subscription("app.acked", 2, 2, 0, 3), subscription("app.hoard", 9000, 9000, 0, 9000)),
                List.of(), List.of());

        // Pasted bare, as copied off a page.
        SubscriptionSearch result = service.find(detail, id);

        assertEquals(filter, result.filter());
        assertEquals(id, result.messageId());
        Map<String, SubscriptionSearch.Row> rows = new HashMap<>();
        result.rows().forEach(row -> rows.put(row.subscription().name(), row));
        assertTrue(rows.get("app.waiting").verdict().startsWith("waiting here"));
        assertNull(rows.get("app.waiting").inFlight(), "found by browse, so the delivering list was not read");
        assertEquals("in flight to billing-worker (CORE, 10.0.0.7:5000) — delivered, not yet acknowledged",
                rows.get("app.held").verdict());
        assertTrue(rows.get("app.acked").verdict().startsWith("not on this queue"),
                "its in-flight messages were checked, so this is a clean no: " + rows.get("app.acked").verdict());
        assertTrue(rows.get("app.hoard").unsearchable());
        assertTrue(rows.get("app.hoard").verdict().contains("more in flight than this tool will read"),
                rows.get("app.hoard").verdict());
        assertEquals(2, result.subscriptionsHolding());
        verify(inFlight, never()).locate(eq("app.waiting"), anyLong(), anyString());
    }

    @Test
    @DisplayName("an ordinary filter never reads the delivering list, and still says what it could not search")
    void ordinaryFilterLeavesInFlightUnsearched()
    {
        QueueBrowseService browse = mock(QueueBrowseService.class);
        given(browse.matching("app.held", "region = 'eu'", AddressDetailService.FIND_LIMIT)).willReturn(List.of());
        InFlightService inFlight = mock(InFlightService.class);
        AddressDetailService service = new AddressDetailService(null, null, null, browse, null, inFlight);

        SubscriptionSearch result = service.find(
                new AddressDetail(null, List.of(subscription("app.held", 3, 3, 0, 3)), List.of(), List.of()),
                "region = 'eu'");

        assertNull(result.messageId());
        assertTrue(result.rows().get(0).verdict().contains("3 in flight to a consumer could not be searched"));
        verifyNoInteractions(inFlight);
    }

    @Test
    @DisplayName("the oldest age is a Long when there is one, and unknown — not an error — otherwise")
    void readsTheOldestAge()
    {
        BrokerSession session = mock(BrokerSession.class);
        ManagementChannel management = mock(ManagementChannel.class);
        given(session.requireManagement()).willReturn(management);
        given(management.attribute(ResourceNames.QUEUE + "app.one", "firstMessageAge")).willReturn(1644L);
        given(management.attribute(ResourceNames.QUEUE + "app.empty", "firstMessageAge")).willReturn(null);
        given(management.attribute(ResourceNames.QUEUE + "app.gone", "firstMessageAge"))
                .willThrow(new BrokerException("Problem while retrieving attribute firstMessageAge"));
        QueueDirectory queues = new QueueDirectory(session);

        assertEquals(1644L, queues.oldestUndeliveredAgeMillis("app.one"));
        assertNull(queues.oldestUndeliveredAgeMillis("app.empty"));
        assertNull(queues.oldestUndeliveredAgeMillis("app.gone"), "a queue that vanished since listing is unknown");
    }

    @Test
    @DisplayName("an empty subscription costs no age read")
    void skipsTheAgeReadForEmptyQueues()
    {
        AddressDirectory addresses = mock(AddressDirectory.class);
        QueueDirectory queues = mock(QueueDirectory.class);
        BrokerInfoService info = mock(BrokerInfoService.class);
        DivertDirectory diverts = mock(DivertDirectory.class);
        given(addresses.overview()).willReturn(
                List.of(new AddressOverview("events", "MULTICAST", 1, 0, 1, 0, false, false, false, List.of())));
        given(addresses.settings("events")).willReturn(AddressSettings.of(Map.of()));
        given(diverts.all()).willReturn(List.of());
        given(queues.onAddress("events"))
                .willReturn(List.of(subscription("app.full", 1, 0, 0, 1), subscription("app.empty", 0, 0, 0, 1)));
        given(queues.oldestUndeliveredAgeMillis("app.full")).willReturn(5000L);
        given(info.consumersOn(anySet())).willReturn(List.of());
        given(info.producers()).willReturn(List.of());

        AddressDetail detail = new AddressDetailService(addresses, queues, info, null, diverts, null).detail("events");

        assertEquals(Map.of("app.full", 5000L), detail.oldestUndeliveredMillis());
        verify(queues, never()).oldestUndeliveredAgeMillis("app.empty");
    }

    private static SubscriptionSearch.Row row(Subscription subscription, int found)
    {
        List<MessageSummary> messages = found == 0 ? List.of() : List.of(mock(MessageSummary.class));
        return new SubscriptionSearch.Row(subscription, messages, false);
    }

    private static Subscription subscription(String name, long messages, long delivering, long scheduled, long added)
    {
        return Subscription.of(name, "events", "MULTICAST", "", "artemis", true, false, false, messages, delivering,
                scheduled, 0, added, 0, 0, 0);
    }
}
