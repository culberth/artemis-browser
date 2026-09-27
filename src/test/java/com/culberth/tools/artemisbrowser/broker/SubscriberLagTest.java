package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        AddressDetailService service = new AddressDetailService(null, null, null, browse, null);
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
        given(info.consumersOn(org.mockito.ArgumentMatchers.anySet())).willReturn(List.of());
        given(info.producers()).willReturn(List.of());

        AddressDetail detail = new AddressDetailService(addresses, queues, info, null, diverts).detail("events");

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
