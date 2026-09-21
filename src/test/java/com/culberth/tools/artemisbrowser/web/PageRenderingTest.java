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

import com.culberth.tools.artemisbrowser.broker.AddressDirectory;
import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerHealth;
import com.culberth.tools.artemisbrowser.broker.BrokerInfoService;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.ConnectionStore;
import com.culberth.tools.artemisbrowser.broker.Finding;
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
    private StuckDiagnosisService diagnosis;

    @MockitoBean
    private ConnectionStore connectionStore;

    @MockitoBean
    private QueueDirectory queueDirectory;

    @MockitoBean
    private QueueBrowseService browseService;

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
