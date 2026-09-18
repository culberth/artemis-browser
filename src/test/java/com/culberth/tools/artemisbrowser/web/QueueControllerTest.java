package com.culberth.tools.artemisbrowser.web;

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
import com.culberth.tools.artemisbrowser.broker.BrowseResult;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.QueueBrowseService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
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
class QueueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrokerSession brokerSession;

    @MockitoBean
    private QueueDirectory queueDirectory;

    @MockitoBean
    private QueueBrowseService browseService;

    @BeforeEach
    void connected() {
        given(brokerSession.isConnected()).willReturn(true);
        given(brokerSession.info()).willReturn(new ConnectionInfo("localhost", 61616, "artemis"));
        given(queueDirectory.queueNames()).willReturn(List.of("orders", "payments"));
    }

    @Test
    @DisplayName("without a connection the user is sent back to the connect form")
    void redirectsWhenNotConnected() throws Exception {
        given(brokerSession.isConnected()).willReturn(false);

        mockMvc.perform(get("/queues").header("Host", "localhost"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("lists queues without browsing anything until one is selected")
    void listsQueuesWithoutBrowsing() throws Exception {
        mockMvc.perform(get("/queues").header("Host", "localhost"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("queueNames", List.of("orders", "payments")))
                .andExpect(model().attributeDoesNotExist("browse"));

        verify(browseService, never()).browse(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("a queue name that the broker did not list is refused, not browsed")
    void refusesUnknownQueueName() throws Exception {
        // Otherwise ?name= is a free-form handle onto any address on the broker.
        mockMvc.perform(get("/queues").param("name", "secrets").header("Host", "localhost"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attributeDoesNotExist("browse"));

        verify(browseService, never()).browse(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("browses the selected queue by its fully-qualified name")
    void browsesSelectedQueueByFqqn() throws Exception {
        QueueStats stats = new QueueStats("orders", "orders.address", "MULTICAST",
                3, 0, 0, 1, 10, 7, true, false);
        given(queueDirectory.stats("orders")).willReturn(stats);
        given(browseService.browse("orders", "orders.address::orders", 50))
                .willReturn(new BrowseResult("orders", 50, false, List.of()));

        mockMvc.perform(get("/queues").param("name", "orders").header("Host", "localhost"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selected", "orders"))
                .andExpect(model().attributeExists("stats", "browse"));

        verify(browseService).browse("orders", "orders.address::orders", 50);
    }

    @Test
    @DisplayName("an oversized limit is clamped rather than passed to the broker")
    void clampsLimit() throws Exception {
        QueueStats stats = new QueueStats("orders", "orders", "ANYCAST",
                0, 0, 0, 0, 0, 0, true, false);
        given(queueDirectory.stats("orders")).willReturn(stats);
        given(browseService.browse(anyString(), anyString(), anyInt()))
                .willReturn(new BrowseResult("orders", 500, false, List.of()));

        mockMvc.perform(get("/queues")
                        .param("name", "orders")
                        .param("limit", "100000")
                        .header("Host", "localhost"))
                .andExpect(status().isOk());

        verify(browseService).browse("orders", "orders", 500);
    }

    @Test
    @DisplayName("a non-loopback Host header is refused before any controller runs")
    void rejectsNonLoopbackHost() throws Exception {
        mockMvc.perform(get("/queues").header("Host", "evil.example.com"))
                .andExpect(status().isForbidden());
    }
}
