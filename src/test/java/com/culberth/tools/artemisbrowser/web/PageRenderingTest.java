package com.culberth.tools.artemisbrowser.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import com.culberth.tools.artemisbrowser.broker.RateTracker;
import com.culberth.tools.artemisbrowser.broker.QueueBehavior;
import com.culberth.tools.artemisbrowser.broker.AddressPressure;
import com.culberth.tools.artemisbrowser.broker.AddressDirectory;
import com.culberth.tools.artemisbrowser.broker.AddressOverview;
import com.culberth.tools.artemisbrowser.broker.AddressRouting;
import com.culberth.tools.artemisbrowser.broker.AddressSettings;
import com.culberth.tools.artemisbrowser.broker.Availability;
import com.culberth.tools.artemisbrowser.broker.BrokerConnection;
import com.culberth.tools.artemisbrowser.broker.BrokerProducer;
import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerHealth;
import com.culberth.tools.artemisbrowser.broker.BrokerInfoService;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ClientDirectory;
import com.culberth.tools.artemisbrowser.broker.ClientView;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.ConnectionStore;
import com.culberth.tools.artemisbrowser.broker.Diagnosis;
import com.culberth.tools.artemisbrowser.broker.Divert;
import com.culberth.tools.artemisbrowser.broker.Finding;
import com.culberth.tools.artemisbrowser.broker.InFlight;
import com.culberth.tools.artemisbrowser.broker.InFlightConsumer;
import com.culberth.tools.artemisbrowser.broker.InFlightLookup;
import com.culberth.tools.artemisbrowser.broker.InFlightMessage;
import com.culberth.tools.artemisbrowser.broker.InFlightService;
import com.culberth.tools.artemisbrowser.broker.ManagementRefusal;
import com.culberth.tools.artemisbrowser.broker.MessageDetail;
import com.culberth.tools.artemisbrowser.broker.MessageExporter;
import com.culberth.tools.artemisbrowser.broker.MessagePage;
import com.culberth.tools.artemisbrowser.broker.MessageSearchService;
import com.culberth.tools.artemisbrowser.broker.MessageSummary;
import com.culberth.tools.artemisbrowser.broker.QueueBrowseService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.broker.QueueOverview;
import com.culberth.tools.artemisbrowser.broker.QueueStats;
import com.culberth.tools.artemisbrowser.broker.RateService;
import com.culberth.tools.artemisbrowser.broker.Rates;
import com.culberth.tools.artemisbrowser.broker.Reading;
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
    private RateService rateService;

    @MockitoBean
    private ClientDirectory clientDirectory;

    @MockitoBean
    private MessageSearchService searchService;

    @MockitoBean
    private MessageExporter exporter;

    @BeforeEach
    void connected()
    {
        given(rateService.observe(org.mockito.ArgumentMatchers.anyList()))
                .willReturn(Rates.none("no earlier reading in this session yet"));
        given(brokerSession.isConnected()).willReturn(true);
        given(brokerSession.info()).willReturn(new ConnectionInfo("localhost", 61616, "artemis"));
        given(brokerInfo.health())
                .willReturn(BrokerHealth.of("2.42.0", "1 day", "STARTED", "node-1", 1, 1, 1, 1024, 5, 10.0d, 90));
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
    @DisplayName("a panel the broker refuses says why, and the panels after it still render")
    void rendersTheBrokerPageWithARefusedPanel() throws Exception
    {
        given(brokerInfo.acceptors()).willThrow(new ManagementRefusal(Availability.DENIED,
                "AMQ229032: User: viewer does not have permission='VIEW' on address mops.broker.getAcceptorsAsJSON"));
        given(brokerInfo.connections())
                .willReturn(List.of(new BrokerConnection("conn-after-the-refusal", "10.0.0.9:5000", "", 1, false)));

        page("/broker").andExpect(content().string(containsString("Not shown: not permitted for this user.")))
                .andExpect(content().string(containsString("mops.broker.getAcceptorsAsJSON")))
                .andExpect(content().string(containsString("conn-after-the-refusal")));
    }

    @Test
    @DisplayName("a health figure the broker would not give is marked with its reason, never shown as zero")
    void rendersMissingHealthFigures() throws Exception
    {
        BrokerHealth full = BrokerHealth.of("2.42.0", "1 day", "STARTED", "node-1", 1, 1, 1, 1024, 5, 10.0d, 90);
        given(brokerInfo.health()).willReturn(new BrokerHealth(full.version(), full.uptime(),
                Reading.failed("the status attribute has no server.state"), full.nodeId(), full.connectionCount(),
                full.sessionCount(), full.consumerCount(), full.memoryUsedBytes(), full.memoryUsedPercent(),
                Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute diskStoreUsage"),
                full.maxDiskPercent(), full.collectedAt()));

        page("/broker").andExpect(content().string(containsString("— not available from this broker")))
                .andExpect(content().string(containsString("state unknown")))
                .andExpect(content().string(not(containsString("0.00%"))))
                .andExpect(content().string(containsString("blocks at 90%")));
    }

    @Test
    @DisplayName("a client page renders its connections, what it consumes with in-flight links, and where it sends")
    void rendersTheClientPage() throws Exception
    {
        given(clientDirectory.find("billing-svc", null)).willReturn(new ClientView("billing-svc", null,
                List.of(new ClientView.Connection("a018fd3b", "172.17.0.1:52716", "artemis", "AMQP",
                        "Mon Sep 28 01:59:40 GMT 2026", 2)),
                List.of(new ClientView.Session("446d24d0", "a018fd3b", 1, 1, "Mon Sep 28 01:59:40 GMT 2026")),
                List.of(new ClientView.Consumer("11", "446d24d0", "client-q", "client-q", "", 9, 5, 4)),
                List.of(new ClientView.Producer("6", "446d24d0", "client-out", 12, 2048, "2026-09-28 01:59:40"),
                        new ClientView.Producer("7", "446d24d0", "", 3, 300, ""))));

        page("/client?id=billing-svc").andExpect(content().string(containsString("billing-svc")))
                .andExpect(content().string(containsString("172.17.0.1:52716")))
                .andExpect(content().string(containsString("href=\"/queues?name=client-q#in-flight\"")))
                .andExpect(content().string(containsString("href=\"/address?name=client-out\"")))
                .andExpect(content().string(containsString("any address")));
    }

    @Test
    @DisplayName("a client with no client id is named by its connection, and the page says why")
    void rendersAnAnonymousClientPage() throws Exception
    {
        given(clientDirectory.find(null, "ea82643f")).willReturn(new ClientView("", "ea82643f",
                List.of(new ClientView.Connection("ea82643f", "172.17.0.1:52720", "artemis", "CORE", "", 1)), List.of(),
                List.of(), List.of()));

        page("/client?connection=ea82643f").andExpect(content().string(containsString("no client id")))
                .andExpect(content().string(containsString("172.17.0.1:52720")))
                .andExpect(content().string(containsString("not reading from any queue")));
    }

    @Test
    @DisplayName("a client that has gone renders as not connected, not as an error page")
    void rendersAMissingClient() throws Exception
    {
        page("/client?id=gone-svc").andExpect(content().string(containsString("No client with id &#39;gone-svc&#39;")))
                .andExpect(content().string(containsString("is connected to this broker now")));
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
                .andExpect(content().string(containsString("conn-1")))
                .andExpect(content().string(containsString("Removed after too many failed delivery attempts")));
    }

    @Test
    @DisplayName("an address at its limit under BLOCK shows usage beside policy and says what producers go through")
    void rendersAnAddressAtItsLimit() throws Exception
    {
        AddressOverview full = new AddressOverview("probe-block", "ANYCAST", 27, 83781, 27, 0, false, false, false,
                List.of()).withStorage(418, 0);
        AddressSettings settings = AddressSettings.of(Map.of("addressFullMessagePolicy", "BLOCK", "maxSizeBytes",
                "20000", "maxSizeMessages", "-1", "pageLimitBytes", "-1", "pageLimitMessages", "-1"));
        given(addressDetail.detail("probe-block"))
                .willReturn(new AddressDetail(full, List.of(), List.of(), List.of(), Map.of(), AddressRouting.NONE,
                        Map.of(), new AddressPressure(full, Reading.of(settings), Reading.of(false),
                                BrokerHealth.of("2.55.0", "1m", "STARTED", "n", 1, 1, 1, 83781, 0, 10, 90))));

        page("/address?name=probe-block").andExpect(content().string(containsString("Storage and limits")))
                .andExpect(content().string(containsString("At its limit (418%)")))
                .andExpect(content().string(containsString("producers are made to wait")))
                .andExpect(content().string(containsString("19.5 KB")))
                .andExpect(content().string(containsString("81.8 KB")))
                .andExpect(content().string(containsString("418% of limit")))
                .andExpect(content().string(containsString("of 1.0 GB")));
    }

    @Test
    @DisplayName("an address paging under PAGE says it is normal, and a blocked one says an operator did it")
    void rendersPagingAndBlockedAddresses() throws Exception
    {
        AddressOverview paging = new AddressOverview("probe-page", "ANYCAST", 40, 21679, 40, 0, true, false, false,
                List.of()).withStorage(108, 9);
        AddressSettings settings = AddressSettings
                .of(Map.of("addressFullMessagePolicy", "PAGE", "maxSizeBytes", "20000"));
        given(addressDetail.detail("probe-page"))
                .willReturn(new AddressDetail(paging, List.of(), List.of(), List.of(), Map.of(), AddressRouting.NONE,
                        Map.of(), new AddressPressure(paging, Reading.of(settings), Reading.of(false), null)));
        given(addressDetail.detail("held"))
                .willReturn(new AddressDetail(paging, List.of(), List.of(), List.of(), Map.of(), AddressRouting.NONE,
                        Map.of(), new AddressPressure(paging, Reading.of(settings), Reading.of(true), null)));

        page("/address?name=probe-page").andExpect(content().string(containsString("That is the policy working")))
                .andExpect(content().string(containsString("paging, 9 pages")));
        page("/address?name=held").andExpect(content().string(containsString("Blocked through management")));
    }

    @Test
    @DisplayName("limits that could not be read say so, and the usage the listing gave still shows")
    void rendersAnAddressWithoutItsLimits() throws Exception
    {
        AddressOverview address = new AddressOverview("events", "MULTICAST", 3, 2048, 3, 0, false, false, false,
                List.of());
        given(addressDetail.detail("events")).willReturn(
                new AddressDetail(address, List.of(), List.of(), List.of(), Map.of(), AddressRouting.NONE, Map.of(),
                        new AddressPressure(address, Reading.missing(Availability.DENIED, "AMQ229032 settings"),
                                Reading.missing(Availability.UNAVAILABLE, "Problem while retrieving attribute"),
                                null)));

        page("/address?name=events").andExpect(content().string(containsString("Not shown: not permitted")))
                .andExpect(content().string(containsString("2.0 KB")))
                .andExpect(content().string(containsString("not available from this broker")));
    }

    @Test
    @DisplayName("the address index marks a full address, and does not mark one merely paging as a problem")
    void rendersStorageBadgesOnTheIndex() throws Exception
    {
        given(addressDirectory.overview()).willReturn(List.of(
                new AddressOverview("probe-fail", "ANYCAST", 7, 21679, 8, 0, true, false, false, List.of())
                        .withStorage(108, 0),
                new AddressOverview("probe-page", "ANYCAST", 40, 21679, 40, 0, true, false, false, List.of())
                        .withStorage(108, 9)));

        page("/addresses").andExpect(content().string(containsString("badge warn\"")))
                .andExpect(content().string(containsString(">full</span>")))
                .andExpect(content().string(containsString("paging, 9 pages")));
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
    @DisplayName("diverts and an age that cannot be read are marked, not shown as none")
    void rendersTheAddressPageWithoutDivertsOrAges() throws Exception
    {
        AddressOverview events = new AddressOverview("events", "MULTICAST", 3, 0, 0, 0, false, false, false, List.of());
        Subscription sub = Subscription.of("events.sub", "events", "MULTICAST", "", "artemis", true, false, false, 3, 0,
                0, 0, 3, 0, 0, 0);
        given(addressDetail.detail("events")).willReturn(new AddressDetail(events, List.of(sub), List.of(), List.of(),
                Map.of(), new AddressRouting(null, null, List.of(), List.of(), java.util.Set.of(), "not supported"),
                Map.of("events.sub", "not permitted for this user — AMQ229032")));

        page("/address?name=events").andExpect(content().string(containsString("Diverts could not be read")))
                .andExpect(content().string(containsString("does not mean there are none")))
                .andExpect(content().string(containsString("not permitted for this user — AMQ229032")));
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
    @DisplayName("rates render on the overview and the queue page with their interval, and a dash where there is none")
    void rendersRates() throws Exception
    {
        given(rateService.observe(org.mockito.ArgumentMatchers.anyList())).willReturn(new Rates(
                Map.of(QUEUE, new com.culberth.tools.artemisbrowser.broker.QueueRate(12.5, 0.04)), 15_000, null));
        given(queueDirectory.stats(QUEUE)).willReturn(stats(QUEUE, 2, 0));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage(QUEUE, null, 1, 50, 2, List.of(summary(1, "ID:aaa"))));

        page("/overview").andExpect(content().string(containsString(">13<")))
                .andExpect(content().string(containsString("&lt;0.1")))
                .andExpect(content().string(containsString("over the last 15s")));
        page("/queues?name=" + QUEUE).andExpect(content().string(containsString(">In/s<")))
                .andExpect(content().string(containsString("Per second over the last 15s")));
    }

    @Test
    @DisplayName("before a second reading, the overview says how to get rates rather than showing zeros")
    void rendersNoRatesYet() throws Exception
    {
        page("/overview").andExpect(content().string(containsString("need two readings")));
    }

    @Test
    @DisplayName("diagnose says when it waited to measure rates itself")
    void rendersDiagnoseSampledRates() throws Exception
    {
        given(diagnosis.run(anyBoolean())).willReturn(
                new Diagnosis(List.of(), 0, 0, new Diagnosis.Measured(new Rates(Map.of(), 3_000, null), true)));

        page("/diagnose").andExpect(content().string(containsString("waited a few seconds to measure rates")))
                .andExpect(content().string(containsString("over the last 3s")));
    }

    @Test
    @DisplayName("expired and killed counts render on the overview, sortable, and on the queue page with a note")
    void rendersExpiredAndKilled() throws Exception
    {
        QueueOverview lossy = new QueueOverview("lossy", "lossy", "ANYCAST", 0, 0, 0, 1, 9, 2, true, false, false, 4,
                3);
        given(queueDirectory.overview()).willReturn(List.of(queue(QUEUE, 2, 0), lossy));
        given(queueDirectory.stats("lossy"))
                .willReturn(new QueueStats("lossy", "lossy", "ANYCAST", 0, 0, 0, 1, 9, 2, true, false, 4, 3));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage("lossy", null, 1, 50, 0, List.of()));

        page("/overview?sort=killed&dir=desc").andExpect(content().string(containsString(">Killed ▾<")))
                .andExpect(content().string(containsString("Expired</strong> and <strong>Killed")));
        page("/queues?name=lossy").andExpect(content().string(containsString(">Killed<")))
                .andExpect(content().string(containsString("7</span> message(s) left this")))
                .andExpect(content().string(containsString("href=\"/address?name=lossy\"")));
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
    @DisplayName("a ring, last-value, exclusive queue explains each setting, with the address default beside it")
    void rendersQueueConfiguration() throws Exception
    {
        QueueBehavior special = behavior("ringSize", "3", "lastValueKey", "k", "exclusive", "true");
        QueueStats base = stats(QUEUE, 3, 0);
        given(queueDirectory.stats(QUEUE)).willReturn(new QueueStats(base.name(), base.address(), base.routingType(), 3,
                0, 0, 0, 10, 0, true, false, 0, 0, special));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage(QUEUE, null, 1, 50, 0, List.of()));
        given(addressDirectory.settings(QUEUE))
                .willReturn(AddressSettings.of(Map.of("defaultNonDestructive", "true", "defaultRingSize", "10")));
        given(queueDirectory.groupCount(QUEUE)).willReturn(Reading.of(2L));

        page("/queues?name=" + QUEUE).andExpect(content().string(containsString("Configuration and behavior")))
                .andExpect(content().string(containsString("ring 3")))
                .andExpect(content().string(containsString("each new message removes the oldest")))
                .andExpect(content().string(containsString("same k value")))
                .andExpect(content().string(containsString("wait as standbys")))
                .andExpect(content().string(containsString("non-destructive by default: true")))
                .andExpect(content().string(containsString("defaultRingSize")))
                .andExpect(content().string(containsString("2 message group(s)")));
    }

    @Test
    @DisplayName("a plain queue says so, and address settings that could not be read say why")
    void rendersAPlainQueue() throws Exception
    {
        QueueStats base = stats(QUEUE, 1, 0);
        given(queueDirectory.stats(QUEUE)).willReturn(new QueueStats(base.name(), base.address(), base.routingType(), 1,
                0, 0, 0, 1, 0, true, false, 0, 0, behavior()));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage(QUEUE, null, 1, 50, 0, List.of()));
        given(addressDirectory.settings(QUEUE)).willThrow(new ManagementRefusal(Availability.DENIED, "AMQ229032"));
        given(queueDirectory.groupCount(QUEUE)).willReturn(Reading.of(0L));

        page("/queues?name=" + QUEUE).andExpect(content().string(containsString("A plain first-in, first-out queue")))
                .andExpect(content().string(containsString("not reported per queue")))
                .andExpect(content().string(containsString("not permitted for this user")));
    }

    @Test
    @DisplayName("the overview badges a queue's configuration from the listing")
    void rendersBehaviorBadgesOnTheOverview() throws Exception
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue(QUEUE, 2, 0).withBehavior(behavior("consumersBeforeDispatch", "2"))));

        page("/overview").andExpect(content().string(containsString("waits for 2 consumers")));
    }

    @Test
    @DisplayName("the queue page draws this session's trend: line, summaries, intervals and a break")
    void rendersTheQueueTrend() throws Exception
    {
        RateTracker tracker = new RateTracker();
        long t0 = 1_790_000_000_000L;
        tracker.observe("b", 3_600_000L, List.of(queue(QUEUE, 10, 0)), t0);
        tracker.observe("b", 3_615_000L, List.of(queue(QUEUE, 40, 0)), t0 + 15_000);
        tracker.observe("b", 3_630_000L, List.of(queue(QUEUE, 40, 0).withId(7)), t0 + 30_000);
        tracker.observe("b", 3_645_000L, List.of(queue(QUEUE, 55, 0).withId(8)), t0 + 45_000);
        given(rateService.trends()).willReturn(tracker.trends());
        given(queueDirectory.stats(QUEUE)).willReturn(stats(QUEUE, 55, 0));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage(QUEUE, null, 1, 50, 0, List.of()));

        page("/queues?name=" + QUEUE).andExpect(content().string(containsString("Recent trend")))
                .andExpect(content().string(containsString("<polyline")))
                .andExpect(content().string(containsString("the queue was deleted and created again")))
                .andExpect(content().string(containsString("Not measured")))
                .andExpect(content().string(containsString("10 → 40")))
                .andExpect(content().string(containsString("kept apart from expired and killed")));
    }

    @Test
    @DisplayName("with one reading the queue page says the trend needs another, and claims nothing older")
    void rendersTheQueueTrendWithOneReading() throws Exception
    {
        RateTracker tracker = new RateTracker();
        tracker.observe("b", 3_600_000L, List.of(queue(QUEUE, 1, 0)), 1_790_000_000_000L);
        given(rateService.trends()).willReturn(tracker.trends());
        given(queueDirectory.stats(QUEUE)).willReturn(stats(QUEUE, 1, 0));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage(QUEUE, null, 1, 50, 0, List.of()));

        page("/queues?name=" + QUEUE).andExpect(content().string(containsString("One reading so far")));
    }

    @Test
    @DisplayName("the overview shows each queue's trend and change")
    void rendersTheOverviewTrend() throws Exception
    {
        RateTracker tracker = new RateTracker();
        tracker.observe("b", 3_600_000L, List.of(queue(QUEUE, 10, 0)), 1_790_000_000_000L);
        tracker.observe("b", 3_615_000L, List.of(queue(QUEUE, 40, 0)), 1_790_000_015_000L);
        given(rateService.trends()).willReturn(tracker.trends());

        page("/overview").andExpect(content().string(containsString("class=\"spark\"")))
                .andExpect(content().string(containsString("+30")))
                .andExpect(content().string(containsString("reaching back about")));
    }

    @Test
    @DisplayName("a filtered page renders with no total: no 'of N', no Last, and Next only when a next page exists")
    void rendersAFilteredPageWithoutATotal() throws Exception
    {
        given(queueDirectory.stats(QUEUE)).willReturn(stats(QUEUE, 1000, 0));
        given(browseService.page(anyString(), any(), anyInt(), anyInt())).willReturn(new MessagePage(QUEUE,
                "region = 'eu'", 2, 2, MessagePage.UNKNOWN, true, List.of(summary(3, "ID:ccc"), summary(4, "ID:ddd"))));

        page("/queues?name=" + QUEUE + "&filter=region = 'eu'&page=2&size=2")
                .andExpect(content().string(containsString("showing 3–4, and more after these")))
                .andExpect(content().string(containsString(">Page 2<")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Last &raquo;"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Last »"))));
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
    @DisplayName("scheduled messages that cannot be listed say so, and the waiting messages still show")
    void rendersTheQueuePageWhenScheduledCannotBeListed() throws Exception
    {
        given(queueDirectory.stats(QUEUE)).willReturn(stats(QUEUE, 1, 1));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage(QUEUE, null, 1, 50, 0, List.of()));
        given(browseService.scheduled(QUEUE)).willThrow(new ManagementRefusal(Availability.DENIED,
                "The broker refused queue.orders.listScheduledMessagesAsJSON() for this user"));

        page("/queues?name=" + QUEUE)
                .andExpect(content().string(containsString("scheduled message(s), and they could not be listed")))
                .andExpect(content().string(containsString("listScheduledMessagesAsJSON")))
                .andExpect(content().string(not(containsString("This queue is empty."))));
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
        given(diagnosis.run(anyBoolean())).willReturn(
                new Diagnosis(List.of(Finding.stuck("Nothing is consuming", "No consumers attached", QUEUE)), 0, 0));

        page("/diagnose").andExpect(content().string(containsString("Nothing is consuming")));
    }

    @Test
    @DisplayName("a finding about a subscription links to both its queue and its address")
    void rendersADiagnoseFindingWithQueueAndAddress() throws Exception
    {
        given(diagnosis.run(anyBoolean())).willReturn(new Diagnosis(List.of(
                new Finding(Finding.STUCK, "Durable subscription 'app.audit' has no subscriber attached", "kept for it",
                        "app.audit", "events"),
                Finding.watch("One consumer holds everything in flight on 'work'", "hoarding", "work")
                        .aboutClient("billing-worker-a", "conn-a")),
                0, 0));

        page("/diagnose").andExpect(content().string(containsString("href=\"/queues?name=app.audit\"")))
                .andExpect(content().string(containsString("href=\"/address?name=events\"")))
                .andExpect(content().string(containsString("href=\"/client?id=billing-worker-a\"")));
    }

    @Test
    @DisplayName("an inferred finding is marked as inferred")
    void rendersAnInferredFinding() throws Exception
    {
        given(diagnosis.run(anyBoolean())).willReturn(new Diagnosis(List.of(Finding
                .atAddress(Finding.STUCK, "'probe-fail' is full, so sends to it are rejected", "at 108%", "probe-fail")
                .inferred()), 0, 0));

        page("/diagnose").andExpect(content().string(containsString(">inferred</span>")))
                .andExpect(content().string(containsString("href=\"/address?name=probe-fail\"")));
    }

    @Test
    @DisplayName("a finding's explanation is shown apart from what was observed")
    void rendersAnExplainedFinding() throws Exception
    {
        given(diagnosis.run(anyBoolean()))
                .willReturn(
                        new Diagnosis(
                                List.of(Finding
                                        .watch("'gated' is waiting for 2 consumers before it dispatches",
                                                "3 message(s) waiting", "gated")
                                        .explainedBy("The queue is configured with consumers-before-dispatch 2")),
                                0, 0));

        page("/diagnose").andExpect(content().string(containsString("May be intended:")))
                .andExpect(content().string(containsString("consumers-before-dispatch 2")));
    }

    @Test
    @DisplayName("the diagnose page renders when it finds nothing")
    void rendersTheDiagnosePageWithNoFindings() throws Exception
    {
        given(diagnosis.run(anyBoolean())).willReturn(new Diagnosis(List.of(), 0, 0));

        page("/diagnose").andExpect(content().string(containsString("Nothing on this broker looks blocked")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("was not checked"))));
    }

    @Test
    @DisplayName("a diagnose page that could not check everything says what, and does not claim all is well")
    void rendersTheDiagnosePageWithUncheckedChecks() throws Exception
    {
        given(diagnosis.run(anyBoolean()))
                .willReturn(new Diagnosis(List.of(), 0, 0, new Diagnosis.Measured(Rates.none("not measured"), false),
                        List.of("Disk use against its limit: not permitted for this user — AMQ229032")));

        page("/diagnose").andExpect(content().string(containsString("Could not check")))
                .andExpect(content().string(containsString("Disk use against its limit: not permitted for this user")))
                .andExpect(content().string(containsString("not everything could be")))
                .andExpect(content().string(not(containsString("Nothing on this broker looks blocked"))));
    }

    @Test
    @DisplayName("the diagnose page says which queues' in-flight ages it did not read")
    void rendersTheDiagnosePageWithInFlightNotRead() throws Exception
    {
        given(diagnosis.run(anyBoolean())).willReturn(new Diagnosis(
                List.of(Finding.watch("One consumer holds everything in flight on 'orders'", "hoarding", QUEUE)), 2,
                31000));

        page("/diagnose").andExpect(content().string(containsString("One consumer holds everything in flight")))
                .andExpect(content().string(containsString("not checked on 2 queue(s) holding 31000 in flight")));
    }

    // --------------------------------------------------------------- helpers

    /** Every page is fetched with a Host the filter allows; an unknown one is 403 before any template runs. */
    private org.springframework.test.web.servlet.ResultActions page(String path) throws Exception
    {
        return mockMvc.perform(get(path).header("Host", "localhost")).andExpect(status().isOk());
    }

    /** A queue's configuration as its listing row gives it, every setting at its default unless overridden. */
    private static QueueBehavior behavior(String... overrides)
    {
        Map<String, String> fields = new java.util.LinkedHashMap<>(Map.of("exclusive", "false", "lastValueKey", "",
                "ringSize", "-1", "groupBuckets", "-1", "groupFirstKey", "", "consumersBeforeDispatch", "0",
                "delayBeforeDispatch", "-1", "purgeOnNoConsumers", "false", "maxConsumers", "-1", "enabled", "true"));
        for (int i = 0; i < overrides.length; i += 2)
        {
            fields.put(overrides[i], overrides[i + 1]);
        }
        tools.jackson.databind.node.ObjectNode node = new tools.jackson.databind.ObjectMapper().createObjectNode();
        fields.forEach(node::put);
        return QueueBehavior.from(node);
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
