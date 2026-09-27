package com.culberth.tools.artemisbrowser.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.culberth.tools.artemisbrowser.broker.AddressDetail;
import com.culberth.tools.artemisbrowser.broker.AddressDetailService;
import com.culberth.tools.artemisbrowser.broker.AddressDirectory;
import com.culberth.tools.artemisbrowser.broker.AddressOverview;
import com.culberth.tools.artemisbrowser.broker.AddressRouting;
import com.culberth.tools.artemisbrowser.broker.AddressSettings;
import com.culberth.tools.artemisbrowser.broker.BrokerProducer;
import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerHealth;
import com.culberth.tools.artemisbrowser.broker.BrokerInfoService;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.ConnectionStore;
import com.culberth.tools.artemisbrowser.broker.Divert;
import com.culberth.tools.artemisbrowser.broker.Finding;
import com.culberth.tools.artemisbrowser.broker.InFlight;
import com.culberth.tools.artemisbrowser.broker.InFlightConsumer;
import com.culberth.tools.artemisbrowser.broker.InFlightLookup;
import com.culberth.tools.artemisbrowser.broker.InFlightMessage;
import com.culberth.tools.artemisbrowser.broker.InFlightService;
import com.culberth.tools.artemisbrowser.broker.MessageDetail;
import com.culberth.tools.artemisbrowser.broker.MessageExporter;
import com.culberth.tools.artemisbrowser.broker.MessagePage;
import com.culberth.tools.artemisbrowser.broker.MessageSearchService;
import com.culberth.tools.artemisbrowser.broker.MessageSummary;
import com.culberth.tools.artemisbrowser.broker.QueueBrowseService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.broker.QueueOverview;
import com.culberth.tools.artemisbrowser.broker.QueueStats;
import com.culberth.tools.artemisbrowser.broker.SavedConnection;
import com.culberth.tools.artemisbrowser.broker.ScheduledMessage;
import com.culberth.tools.artemisbrowser.broker.SearchResult;
import com.culberth.tools.artemisbrowser.broker.StuckDiagnosisService;
import com.culberth.tools.artemisbrowser.broker.SubscriberConsumer;
import com.culberth.tools.artemisbrowser.broker.Subscription;
import com.culberth.tools.artemisbrowser.broker.SubscriptionSearch;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * That each page actually renders.
 *
 * <p>
 * Written after `/broker` was found returning 500 in a deployed environment — and had been since Phase 5, when the
 * shared refresh fragment gained a parameter and this template started passing SpEL's empty-map literal {@code {:}},
 * which Thymeleaf's fragment-expression parser cannot read. Nothing caught it because no test rendered the template:
 * the controller tests assert on model attributes, which are populated perfectly well right up until the view blows up.
 *
 * <p>
 * So these assert on the rendered HTML rather than the model. They are deliberately shallow — a template that parses
 * and produces its own heading is the whole bar, because the failure being guarded against is a page that cannot render
 * at all. Every template under {@code templates/} has a case here, and a new page is not covered until it does; the
 * original break lived in a template that no test had ever asked to render.
 *
 * <p>
 * The branches matter as much as the pages. A template's error state, its empty state and its populated state are
 * different regions of markup, and the populated one is the region a fixture has to work to reach — so rendering only
 * the happy path would leave a page half-covered while reading as done.
 */
@WebMvcTest(
{ BrokerController.class, ConnectionController.class, DiagnoseController.class, LoginController.class,
        QueueController.class, SearchController.class
})
@WithMockUser
// A login configured, as on a shared host: the arrangement with a sign-in page and a signed-in user.
// Without one, on loopback, there is neither — LocalWithoutLoginTest covers that.
@org.springframework.test.context.TestPropertySource(properties =
{ "artemis.auth.username=tester",
        "artemis.auth.password-hash=$2a$10$f8/orV9lQp75eS5gnQUXeOFlMtJNzZ1egoONu7Rik1pltb/uUXgHO"
})
class PageRenderingTest
{

    private static final String QUEUE = "orders";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrokerSession brokerSession;

    @MockitoBean
    private BrokerInfoService brokerInfo;

    @MockitoBean
    private AddressDirectory addressDirectory;

    @MockitoBean
    private AddressDetailService addressDetail;

    @MockitoBean
    private StuckDiagnosisService diagnosis;

    @MockitoBean
    private ConnectionStore connectionStore;

    @MockitoBean
    private QueueDirectory queueDirectory;

    @MockitoBean
    private QueueBrowseService browseService;

    @MockitoBean
    private InFlightService inFlightService;

    @MockitoBean
    private MessageSearchService searchService;

    @MockitoBean
    private MessageExporter exporter;

    @BeforeEach
    void connected()
    {
        given(brokerSession.isConnected()).willReturn(true);
        given(brokerSession.info()).willReturn(new ConnectionInfo("localhost", 61616, "artemis"));
        given(brokerInfo.health())
                .willReturn(new BrokerHealth("2.42.0", "1 day", "STARTED", "node-1", 1, 1, 1, 1024, 5, 10.0d, 90));
        given(brokerInfo.acceptors()).willReturn(List.of());
        given(brokerInfo.connections()).willReturn(List.of());
        given(brokerInfo.consumers()).willReturn(List.of());
        given(brokerInfo.producers()).willReturn(List.of());
        given(addressDirectory.overview()).willReturn(List.of());
        given(queueDirectory.overview()).willReturn(List.of(queue(QUEUE, 2, 0)));
        given(connectionStore.all()).willReturn(List.of());
    }

    // ---------------------------------------------------------------- broker

    @Test
    @DisplayName("the broker page renders, fragment arguments and all")
    void rendersTheBrokerPage() throws Exception
    {
        page("/broker").andExpect(content().string(containsString("2.42.0")))
                // The refresh control comes from the shared fragment, which is what broke.
                .andExpect(content().string(containsString("Auto-refresh")));
    }

    @Test
    @DisplayName("the broker page still renders when the broker cannot be read")
    void rendersTheBrokerPageOnError()
    {
        given(brokerInfo.health()).willThrow(new BrokerException("nope"));

        Assertions.assertDoesNotThrow(() -> page("/broker"));
    }

    @Test
    @DisplayName("the addresses page renders")
    void rendersTheAddressesPage() throws Exception
    {
        page("/addresses").andExpect(content().string(containsString("Addresses")));
    }

    @Test
    @DisplayName("one address renders its subscriptions, consumers and producers")
    void rendersTheAddressPage() throws Exception
    {
        Subscription durable = Subscription.of("app-1.audit", "events", "MULTICAST", "AMQPriority > 3", "artemis", true,
                false, false, 6, 0, 0, 0, 6, 0, 0, 0);
        Subscription temporary = Subscription.of("0d6f-uuid", "events", "MULTICAST", "", "artemis", false, true, false,
                0, 0, 0, 1, 0, 0, 0, 0);
        AddressOverview events = new AddressOverview("events", "MULTICAST", 6, 2048, 6, 0, false, false, false,
                List.of());
        Subscription waiting = Subscription.of("app-2.billing", "events", "MULTICAST", "", "artemis", true, false,
                false, 2, 0, 0, 1, 6, 4, 0, 0);
        AddressDetail detail = new AddressDetail(events, List.of(durable, temporary, waiting),
                List.of(new SubscriberConsumer("9", "0d6f-uuid", "live-client", "artemis", "10.0.0.7:5000", "CORE", "",
                        3, 3)),
                List.of(new BrokerProducer("p1", "events", "conn-1", "2026-09-27 10:00:00", 6, 1200, false)),
                Map.of("app-1.audit", 7_500_000L, "app-2.billing", 40_000L));
        given(addressDetail.detail("events")).willReturn(detail);
        given(addressDetail.find(detail, "orderId = 'A'")).willReturn(new SubscriptionSearch("orderId = 'A'",
                List.of(new SubscriptionSearch.Row(durable, List.of(summary(1, "ID:held")), false),
                        new SubscriptionSearch.Row(temporary, List.of(), false))));

        page("/address?name=events&find=orderId = 'A'").andExpect(content().string(containsString("app-1.audit")))
                .andExpect(content().string(containsString("2h 5m")))
                .andExpect(content().string(containsString("furthest behind")))
                .andExpect(content().string(containsString("ID:held")))
                .andExpect(content().string(containsString("not on this queue")))
                .andExpect(content().string(containsString("durable subscription")))
                .andExpect(content().string(containsString("AMQPriority &gt; 3")))
                .andExpect(content().string(containsString("live-client")))
                .andExpect(content().string(containsString("conn-1")));
    }

    @Test
    @DisplayName("an address lookup by message ID renders the subscription holding it in flight, linked to the panel")
    void rendersTheAddressPageWithAnInFlightHit() throws Exception
    {
        Subscription held = Subscription.of("app-1.audit", "events", "MULTICAST", "", "artemis", true, false, false, 3,
                3, 0, 1, 3, 0, 0, 0);
        AddressOverview events = new AddressOverview("events", "MULTICAST", 3, 2048, 3, 0, false, false, false,
                List.of());
        AddressDetail detail = new AddressDetail(events, List.of(held), List.of(), List.of());
        given(addressDetail.detail("events")).willReturn(detail);
        InFlightConsumer holder = new InFlightConsumer("", "c", "s", "0", List.of(), new SubscriberConsumer("7",
                "app-1.audit", "audit-reader", "artemis", "10.0.0.9:6000", "CORE", "", 3, 0), 3L);
        given(addressDetail.find(detail, "ID:held-1")).willReturn(
                new SubscriptionSearch("AMQUserID = 'ID:held-1'", "ID:held-1", List.of(new SubscriptionSearch.Row(held,
                        List.of(), false, new InFlightLookup("app-1.audit", true, holder, null)))));

        page("/address?name=events&find=ID:held-1")
                .andExpect(content().string(containsString("in flight to audit-reader (CORE, 10.0.0.9:6000)")))
                .andExpect(content().string(containsString("href=\"/queues?name=app-1.audit#in-flight\"")))
                .andExpect(content().string(containsString("ID:held-1 — in flight")))
                .andExpect(content().string(containsString("Looking up one message by its ID")));
    }

    @Test
    @DisplayName("the address page renders its settings and diverts, flagging a missing expiry address")
    void rendersWhereElseMessagesGo() throws Exception
    {
        AddressOverview alerts = new AddressOverview("alerts", "MULTICAST", 0, 0, 0, 0, false, false, false, List.of());
        Subscription subscriber = Subscription.of("app.alerts", "alerts", "MULTICAST", "", "artemis", true, false,
                false, 0, 0, 0, 1, 0, 0, 0, 0);
        AddressRouting routing = new AddressRouting(
                AddressSettings
                        .of(Map.of("deadLetterAddress", "DLQ", "expiryAddress", "ExpiryGone",
                                "addressFullMessagePolicy", "PAGE", "maxSizeBytes", "-1", "autoCreateQueues", "true")),
                null,
                List.of(new Divert("alerts-archive", "alerts", "alerts.archive", "severity = 'high'", true, "PASS",
                        "")),
                List.of(new Divert("fan-in", "legacy.alerts", "alerts", "", false, "PASS", "")),
                java.util.Set.of("alerts", "DLQ"));
        given(addressDetail.detail("alerts"))
                .willReturn(new AddressDetail(alerts, List.of(subscriber), List.of(), List.of(), Map.of(), routing));

        page("/address?name=alerts").andExpect(content().string(containsString("Where else its messages go")))
                .andExpect(content().string(containsString("href=\"/address?name=DLQ\"")))
                .andExpect(content().string(containsString("not on the broker")))
                .andExpect(content().string(containsString("alerts-archive")))
                .andExpect(content().string(containsString("An <strong>exclusive</strong> divert")))
                .andExpect(content().string(containsString("legacy.alerts")))
                .andExpect(content().string(containsString("not set")));
    }

    @Test
    @DisplayName("settings that cannot be read say so without taking the address page down")
    void rendersTheAddressPageWithoutSettings() throws Exception
    {
        AddressOverview events = new AddressOverview("events", "MULTICAST", 0, 0, 0, 0, false, false, false, List.of());
        given(addressDetail.detail("events")).willReturn(new AddressDetail(events, List.of(), List.of(), List.of(),
                Map.of(), new AddressRouting(null, "refused", List.of(), List.of(), java.util.Set.of())));

        page("/address?name=events").andExpect(content().string(containsString("could not be read: refused")))
                .andExpect(content().string(containsString("Subscriptions")));
    }

    @Test
    @DisplayName("a filter the broker rejects fails the search, not the address page")
    void rendersTheAddressPageWhenTheSearchFails() throws Exception
    {
        AddressOverview events = new AddressOverview("events", "MULTICAST", 0, 0, 0, 0, false, false, false, List.of());
        AddressDetail detail = new AddressDetail(events, List.of(), List.of(), List.of());
        given(addressDetail.detail("events")).willReturn(detail);
        given(addressDetail.find(detail, "((")).willThrow(new BrokerException("bad filter"));

        page("/address?name=events&find=((").andExpect(content().string(containsString("bad filter")))
                .andExpect(content().string(containsString("Subscriptions")));
    }

    @Test
    @DisplayName("an address the broker does not have says so rather than rendering empty tables")
    void rendersTheAddressPageForAnUnknownAddress() throws Exception
    {
        page("/address?name=gone").andExpect(content().string(containsString("no address named")));
    }

    @Test
    @DisplayName("the address page still renders when the broker cannot be read")
    void rendersTheAddressPageOnError() throws Exception
    {
        given(addressDetail.detail("events")).willThrow(new BrokerException("nope"));

        page("/address?name=events").andExpect(content().string(containsString("nope")));
    }

    // ------------------------------------------------------------ connect in

    @Test
    @DisplayName("the connect page renders when nothing is connected")
    void rendersTheConnectPage() throws Exception
    {
        given(brokerSession.isConnected()).willReturn(false);

        page("/").andExpect(content().string(containsString("Connect to a broker")));
    }

    @Test
    @DisplayName("the connect page renders a remembered connection")
    void rendersTheConnectPageWithSavedConnections() throws Exception
    {
        given(brokerSession.isConnected()).willReturn(false);
        given(connectionStore.all()).willReturn(List.of(new SavedConnection("prod", "broker.example", 61616, "app")));

        page("/").andExpect(content().string(containsString("Saved connections")))
                .andExpect(content().string(containsString("broker.example")));
    }

    @Test
    @DisplayName("the login page renders")
    void rendersTheLoginPage() throws Exception
    {
        page("/login").andExpect(content().string(containsString("Sign in")));
    }

    // --------------------------------------------------------------- queues

    @Test
    @DisplayName("the overview renders, fragment arguments and all")
    void rendersTheOverviewPage() throws Exception
    {
        page("/overview").andExpect(content().string(containsString(QUEUE)))
                // The overview passes viewParams into the same fragment the broker page broke on.
                .andExpect(content().string(containsString("Auto-refresh")));
    }

    @Test
    @DisplayName("the overview renders when the broker cannot be read")
    void rendersTheOverviewPageOnError() throws Exception
    {
        given(queueDirectory.overview()).willThrow(new BrokerException("listQueues failed"));

        page("/overview").andExpect(content().string(containsString("listQueues failed")));
    }

    @Test
    @DisplayName("the queue page renders with no queue chosen")
    void rendersTheQueuePageUnselected() throws Exception
    {
        page("/queues").andExpect(content().string(containsString(QUEUE)));
    }

    @Test
    @DisplayName("the queue page renders a page of messages")
    void rendersTheQueuePageWithMessages() throws Exception
    {
        given(queueDirectory.stats(QUEUE)).willReturn(stats(QUEUE, 2, 0));
        given(browseService.page(anyString(), any(), anyInt(), anyInt())).willReturn(
                new MessagePage(QUEUE, null, 1, 50, 2, List.of(summary(1, "ID:aaa"), summary(2, "ID:bbb"))));

        page("/queues?name=" + QUEUE).andExpect(content().string(containsString("ID:aaa")))
                .andExpect(content().string(containsString("ID:bbb")));
    }

    @Test
    @DisplayName("the queue page renders the scheduled-messages panel")
    void rendersTheQueuePageWithScheduledMessages() throws Exception
    {
        given(queueDirectory.stats(QUEUE)).willReturn(stats(QUEUE, 1, 1));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage(QUEUE, null, 1, 50, 0, List.of()));
        // browse does not return scheduled messages at all, so this panel is the only place they
        // appear — and it renders from a different fixture than the table above it.
        given(browseService.scheduled(QUEUE)).willReturn(List.of(new ScheduledMessage("ID:sched", 7L, "TextMessage", 4,
                true, "2026-09-20 10:00:00", "2026-09-21 10:00:00", false, Map.of())));

        page("/queues?name=" + QUEUE).andExpect(content().string(containsString("ID:sched")));
    }

    @Test
    @DisplayName("a queue whose every message is in flight says so, rather than 'empty'")
    void rendersTheQueuePageWithEverythingInFlight() throws Exception
    {
        given(queueDirectory.stats(QUEUE))
                .willReturn(new QueueStats(QUEUE, QUEUE, "ANYCAST", 3, 3, 0, 1, 3, 0, true, false));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage(QUEUE, null, 1, 50, 0, List.of()));

        page("/queues?name=" + QUEUE).andExpect(content().string(containsString("in flight to a consumer")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("This queue is empty."))));
    }

    @Test
    @DisplayName("the in-flight panel lists each consumer's messages, says why there is no body, and is linked to")
    void rendersTheInFlightPanel() throws Exception
    {
        QueueStats stats = new QueueStats(QUEUE, QUEUE, "ANYCAST", 3, 3, 0, 2, 3, 0, true, false);
        given(queueDirectory.stats(QUEUE)).willReturn(stats);
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage(QUEUE, null, 1, 50, 0, List.of()));
        InFlightMessage held = new InFlightMessage("ID:held-1", 30L, "Text", 4, true, 1L, "2026-09-27 10:00:00",
                "4m 12s", Map.of("region", "eu"));
        given(inFlightService.inFlight(stats)).willReturn(new InFlight(QUEUE, 3, 5000, false, false, List.of(
                new InFlightConsumer("ServerConsumer [id=c:s:0, filter=null]", "c", "s", "0", List.of(held),
                        new SubscriberConsumer("7", QUEUE, "billing-worker", "artemis", "10.0.0.7:5000", "AMQP", "", 3,
                                0),
                        3L),
                new InFlightConsumer("Unrecognised [thing]", null, null, null, List.of(held)))));

        page("/queues?name=" + QUEUE).andExpect(content().string(containsString("id=\"in-flight\"")))
                .andExpect(content().string(containsString("href=\"#in-flight\"")))
                .andExpect(content().string(containsString("billing-worker")))
                .andExpect(content().string(containsString("10.0.0.7:5000")))
                .andExpect(content().string(containsString("holding 3")))
                .andExpect(content().string(containsString("ID:held-1")))
                .andExpect(content().string(containsString("4m 12s ago")))
                .andExpect(content().string(containsString("region=eu")))
                .andExpect(content().string(containsString("no body")))
                .andExpect(content().string(containsString("2 more held by this consumer")))
                .andExpect(content().string(containsString("Unrecognised [thing]")));
    }

    @Test
    @DisplayName("over the limit, the in-flight panel names the holders and says the messages were not read")
    void rendersTheInFlightPanelOverTheLimit() throws Exception
    {
        QueueStats stats = new QueueStats(QUEUE, QUEUE, "ANYCAST", 9000, 9000, 0, 1, 9000, 0, true, false);
        given(queueDirectory.stats(QUEUE)).willReturn(stats);
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage(QUEUE, "region = 'eu'", 1, 50, 0, List.of()));
        given(inFlightService.inFlight(stats)).willReturn(new InFlight(QUEUE, 9000, 5000, true, false,
                List.of(new InFlightConsumer("", "c", "s", "0", List.of(), null, 9000L))));

        page("/queues?name=" + QUEUE + "&filter=region = 'eu'")
                .andExpect(content().string(containsString("more than the 5000 this page will list")))
                .andExpect(content().string(containsString("c:s:0")))
                .andExpect(content().string(containsString("holding 9000")))
                .andExpect(content().string(containsString("filter above is not applied here")))
                .andExpect(content().string(containsString("listed below")));
    }

    @Test
    @DisplayName("a failure reading in-flight messages leaves the rest of the queue page standing")
    void rendersTheQueuePageWhenInFlightFails() throws Exception
    {
        QueueStats stats = new QueueStats(QUEUE, QUEUE, "ANYCAST", 3, 1, 0, 1, 3, 0, true, false);
        given(queueDirectory.stats(QUEUE)).willReturn(stats);
        given(browseService.page(anyString(), any(), anyInt(), anyInt())).willReturn(
                new MessagePage(QUEUE, null, 1, 50, 2, List.of(summary(1, "ID:aaa"), summary(2, "ID:bbb"))));
        given(inFlightService.inFlight(stats)).willThrow(new BrokerException("delivering list unavailable"));

        page("/queues?name=" + QUEUE).andExpect(content().string(containsString("ID:aaa")))
                .andExpect(content().string(containsString("delivering list unavailable")));
    }

    @Test
    @DisplayName("the queue page renders a filter rejected by the broker")
    void rendersTheQueuePageOnError() throws Exception
    {
        given(queueDirectory.stats(QUEUE)).willReturn(stats(QUEUE, 2, 0));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willThrow(new BrokerException("Invalid filter"));

        page("/queues?name=" + QUEUE + "&filter=JMSPriority%3D4")
                .andExpect(content().string(containsString("Invalid filter")));
    }

    // -------------------------------------------------------------- message

    @Test
    @DisplayName("the message page renders one message in full")
    void rendersTheMessagePage() throws Exception
    {
        given(queueDirectory.stats(QUEUE)).willReturn(stats(QUEUE, 1, 0));
        given(browseService.detail(anyString(), anyString(), anyString())).willReturn(detail("ID:aaa"));

        page("/message?name=" + QUEUE + "&id=ID:aaa").andExpect(content().string(containsString("ID:aaa")))
                .andExpect(content().string(containsString("the body")))
                .andExpect(content().string(containsString("orderNumber")));
    }

    @Test
    @DisplayName("the message page renders when the message has since gone")
    void rendersTheMessagePageWhenMissing() throws Exception
    {
        given(queueDirectory.stats(QUEUE)).willReturn(stats(QUEUE, 0, 0));
        given(browseService.detail(anyString(), anyString(), anyString())).willReturn(null);

        page("/message?name=" + QUEUE + "&id=ID:gone").andExpect(content().string(containsString("no longer on")));
    }

    // --------------------------------------------------------- search, stuck

    @Test
    @DisplayName("the search page renders before anything is searched for")
    void rendersTheSearchPageEmpty() throws Exception
    {
        page("/search").andExpect(content().string(containsString("Search every queue")));
    }

    @Test
    @DisplayName("the search page renders results across queues")
    void rendersTheSearchPageWithResults() throws Exception
    {
        given(searchService.search(anyString(), anyBoolean())).willReturn(new SearchResult("count=1", 3, 1, false,
                List.of(new SearchResult.QueueMatches(QUEUE, 1, false, List.of(summary(1, "ID:aaa"))))));

        page("/search?filter=count%3D1").andExpect(content().string(containsString(QUEUE)))
                .andExpect(content().string(containsString("ID:aaa")));
    }

    @Test
    @DisplayName("a message-ID search renders where the message is in flight, and what it could not check")
    void rendersTheSearchPageWithAnInFlightHit() throws Exception
    {
        InFlightMessage held = new InFlightMessage("ID:held-1", 30L, "Text", 4, true, 1L, "2026-09-27 10:00:00",
                "4m 12s", Map.of("region", "eu"));
        InFlightConsumer holder = new InFlightConsumer("", "c", "s", "0", List.of(held),
                new SubscriberConsumer("7", QUEUE, "billing-worker", "artemis", "10.0.0.7:5000", "AMQP", "", 3, 0), 3L);
        given(searchService.search(anyString(), anyBoolean())).willReturn(new SearchResult("AMQUserID = 'ID:held-1'", 3,
                0, false, List.of(), "ID:held-1", List.of(new InFlightLookup(QUEUE, true, holder, held)), 9000));

        page("/search?filter=ID:held-1")
                .andExpect(content().string(containsString("in flight to billing-worker (AMQP, 10.0.0.7:5000)")))
                .andExpect(content().string(containsString("href=\"/queues?name=orders#in-flight\"")))
                .andExpect(content().string(containsString("4m 12s ago")))
                .andExpect(content().string(containsString("0 in 0 of 3 queues, and in flight on 1")))
                .andExpect(content().string(containsString("9000 message(s) in flight could not be checked")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Nothing matched"))));
    }

    @Test
    @DisplayName("an ordinary search says how many in-flight messages it could not look at")
    void rendersTheSearchPageWithInFlightNotSearched() throws Exception
    {
        given(searchService.search(anyString(), anyBoolean()))
                .willReturn(new SearchResult("region = 'eu'", 3, 0, false, List.of(), null, List.of(), 7));

        page("/search?filter=region = 'eu'").andExpect(content().string(containsString("Nothing matched")))
                .andExpect(content().string(containsString("7 message(s) in flight to a consumer")))
                .andExpect(content().string(containsString("Look up a single message by its ID")));
    }

    @Test
    @DisplayName("the search page renders a filter rejected by the broker")
    void rendersTheSearchPageOnError() throws Exception
    {
        given(searchService.search(anyString(), anyBoolean())).willThrow(new BrokerException("Invalid filter"));

        page("/search?filter=JMSPriority%3D4").andExpect(content().string(containsString("Invalid filter")))
                // The advice the error carries is the point of the page failing gracefully.
                .andExpect(content().string(containsString("AMQPriority")));
    }

    @Test
    @DisplayName("the diagnose page renders its findings")
    void rendersTheDiagnosePage() throws Exception
    {
        given(diagnosis.diagnose(anyBoolean()))
                .willReturn(List.of(Finding.stuck("Nothing is consuming", "No consumers attached", QUEUE)));

        page("/diagnose").andExpect(content().string(containsString("Nothing is consuming")));
    }

    @Test
    @DisplayName("a finding about a subscription links to both its queue and its address")
    void rendersADiagnoseFindingWithQueueAndAddress() throws Exception
    {
        given(diagnosis.diagnose(anyBoolean())).willReturn(List.of(new Finding(Finding.STUCK,
                "Durable subscription 'app.audit' has no subscriber attached", "kept for it", "app.audit", "events")));

        page("/diagnose").andExpect(content().string(containsString("href=\"/queues?name=app.audit\"")))
                .andExpect(content().string(containsString("href=\"/address?name=events\"")));
    }

    @Test
    @DisplayName("the diagnose page renders when it finds nothing")
    void rendersTheDiagnosePageWithNoFindings() throws Exception
    {
        given(diagnosis.diagnose(anyBoolean())).willReturn(List.of());

        Assertions.assertDoesNotThrow(() -> page("/diagnose"));
    }

    // --------------------------------------------------------------- helpers

    /** Every page is fetched with a Host the filter allows; an unknown one is 403 before any template runs. */
    private org.springframework.test.web.servlet.ResultActions page(String path) throws Exception
    {
        return mockMvc.perform(get(path).header("Host", "localhost")).andExpect(status().isOk());
    }

    private static QueueOverview queue(String name, long messages, long scheduled)
    {
        return new QueueOverview(name, name, "ANYCAST", messages, 0, scheduled, 0, messages, 0, true, false, false);
    }

    private static QueueStats stats(String name, long messages, long scheduled)
    {
        return new QueueStats(name, name, "ANYCAST", messages, 0, scheduled, 0, messages, 0, true, false);
    }

    private static MessageSummary summary(long position, String id)
    {
        return new MessageSummary(position, id, id, "TextMessage", 1_758_000_000_000L, "2026-09-20 10:00:00", 4, true,
                false, 512, "CORE", false, Map.of("orderNumber", "1"), "a body preview", false);
    }

    private static MessageDetail detail(String id)
    {
        return new MessageDetail(QUEUE, id, null, "TextMessage", QUEUE, "2026-09-20 10:00:00", "never", 4, true, false,
                0, null, false, "the body", false, Map.of("orderNumber", "1"));
    }
}
