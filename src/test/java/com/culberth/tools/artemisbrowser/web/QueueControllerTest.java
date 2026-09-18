package com.culberth.tools.artemisbrowser.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.MessagePage;
import com.culberth.tools.artemisbrowser.broker.QueueBrowseService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.broker.QueueOverview;
import com.culberth.tools.artemisbrowser.broker.QueueStats;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QueueController.class)
class QueueControllerTest
{

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrokerSession brokerSession;

    @MockitoBean
    private QueueDirectory queueDirectory;

    @MockitoBean
    private QueueBrowseService browseService;

    @BeforeEach
    void connected()
    {
        given(brokerSession.isConnected()).willReturn(true);
        given(brokerSession.info()).willReturn(new ConnectionInfo("localhost", 61616, "artemis"));
        given(queueDirectory.overview()).willReturn(List.of(overview("orders"), overview("payments")));
    }

    @Test
    @DisplayName("without a connection the user is sent back to the connect form")
    void redirectsWhenNotConnected() throws Exception
    {
        given(brokerSession.isConnected()).willReturn(false);

        mockMvc.perform(get("/queues").header("Host", "localhost")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("lists queues without browsing anything until one is selected")
    void listsQueuesWithoutBrowsing() throws Exception
    {
        mockMvc.perform(get("/queues").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(model().attribute("queueNames", List.of("orders", "payments")))
                .andExpect(model().attributeDoesNotExist("messages"));

        verify(browseService, never()).page(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a queue name that the broker did not list is refused, not browsed")
    void refusesUnknownQueueName() throws Exception
    {
        // Otherwise ?name= is a free-form handle onto any address on the broker.
        mockMvc.perform(get("/queues").param("name", "secrets").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(model().attributeExists("error")).andExpect(model().attributeDoesNotExist("messages"));

        verify(browseService, never()).page(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("browses the selected queue, passing the filter through")
    void browsesSelectedQueueWithFilter() throws Exception
    {
        given(queueDirectory.stats("orders")).willReturn(stats("orders", "orders"));
        given(browseService.page("orders", "AMQPriority > 4", 1, 50))
                .willReturn(new MessagePage("orders", "AMQPriority > 4", 1, 50, 0, List.of()));

        mockMvc.perform(
                get("/queues").param("name", "orders").param("filter", "AMQPriority > 4").header("Host", "localhost"))
                .andExpect(status().isOk()).andExpect(model().attribute("selected", "orders"))
                .andExpect(model().attributeExists("stats", "messages"));

        verify(browseService).page("orders", "AMQPriority > 4", 1, 50);
    }

    @Test
    @DisplayName("page size is clamped and the page number never drops below 1")
    void clampsPaging() throws Exception
    {
        given(queueDirectory.stats("orders")).willReturn(stats("orders", "orders"));
        given(browseService.page(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage("orders", "", 1, 500, 0, List.of()));

        mockMvc.perform(get("/queues").param("name", "orders").param("size", "100000").param("page", "-3")
                .header("Host", "localhost")).andExpect(status().isOk());

        verify(browseService).page("orders", null, 1, 500);
    }

    @Test
    @DisplayName("an unrecognised auto-refresh interval falls back to off")
    void rejectsArbitraryRefreshInterval() throws Exception
    {
        // The value lands in a meta refresh, so it is not free-form input.
        mockMvc.perform(get("/queues").param("refresh", "1").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(model().attribute("refresh", 0));
    }

    @Test
    @DisplayName("the overview lists every queue with its counters")
    void overviewListsQueues() throws Exception
    {
        mockMvc.perform(get("/overview").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(model().attributeExists("queues"));
    }

    @Test
    @DisplayName("a message on a queue the broker does not have is refused")
    void refusesMessageOnUnknownQueue() throws Exception
    {
        given(queueDirectory.stats("secrets")).willReturn(null);

        mockMvc.perform(get("/message").param("name", "secrets").param("id", "ID:1").header("Host", "localhost"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));

        verify(browseService, never()).detail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a message that has since left the queue reports that, rather than failing")
    void reportsVanishedMessage() throws Exception
    {
        given(queueDirectory.stats("orders")).willReturn(stats("orders", "orders"));
        given(browseService.detail("orders", "orders", "ID:gone")).willReturn(null);

        mockMvc.perform(get("/message").param("name", "orders").param("id", "ID:gone").header("Host", "localhost"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
    }

    @Test
    @DisplayName("a non-loopback Host header is refused before any controller runs")
    void rejectsNonLoopbackHost() throws Exception
    {
        mockMvc.perform(get("/queues").header("Host", "evil.example.com")).andExpect(status().isForbidden());
    }

    private QueueOverview overview(String name)
    {
        return new QueueOverview(name, name, "ANYCAST", 0, 0, 0, 0, 0, 0, true, false, false);
    }

    private QueueStats stats(String name, String address)
    {
        return new QueueStats(name, address, "ANYCAST", 0, 0, 0, 0, 0, 0, true, false);
    }
}
