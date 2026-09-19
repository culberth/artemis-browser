package com.culberth.tools.artemisbrowser.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.MessageExporter;
import com.culberth.tools.artemisbrowser.broker.MessagePage;
import com.culberth.tools.artemisbrowser.broker.MessageSearchService;
import com.culberth.tools.artemisbrowser.broker.MessageSummary;
import com.culberth.tools.artemisbrowser.broker.QueueBrowseService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.broker.QueueOverview;
import com.culberth.tools.artemisbrowser.broker.SearchResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SearchController.class)
@Import(MessageExporter.class)
class SearchControllerTest
{

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrokerSession brokerSession;

    @MockitoBean
    private MessageSearchService searchService;

    @MockitoBean
    private QueueDirectory queueDirectory;

    @MockitoBean
    private QueueBrowseService browseService;

    @BeforeEach
    void connected()
    {
        given(brokerSession.isConnected()).willReturn(true);
        given(brokerSession.info()).willReturn(new ConnectionInfo("localhost", 61616, "artemis"));
        given(queueDirectory.overview()).willReturn(
                List.of(new QueueOverview("orders", "orders", "ANYCAST", 0, 0, 0, 0, 0, 0, true, false, false)));
    }

    @Test
    @DisplayName("the search page loads without searching until a filter is given")
    void noFilterNoSearch() throws Exception
    {
        mockMvc.perform(get("/search").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("result"));

        verify(searchService, never()).search(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("a filter runs the search and passes the internal-queues choice through")
    void runsSearch() throws Exception
    {
        given(searchService.search("count = 1", true))
                .willReturn(new SearchResult("count = 1", 3, 0, false, List.of()));

        mockMvc.perform(
                get("/search").param("filter", "count = 1").param("internal", "true").header("Host", "localhost"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("result"));

        verify(searchService).search("count = 1", true);
    }

    @Test
    @DisplayName("export refuses a queue the broker did not list")
    void exportRefusesUnknownQueue() throws Exception
    {
        mockMvc.perform(get("/export").param("name", "secrets").header("Host", "localhost"))
                .andExpect(status().isNotFound());

        verify(browseService, never()).pageForExport(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("CSV export is served as an attachment with a safe filename")
    void exportsCsv() throws Exception
    {
        given(browseService.pageForExport(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage("orders", "", 1, 5000, 1, List.of(new MessageSummary(1, "ID:1", "1", "Text",
                        0L, "", 4, true, false, 10, "CORE", "body", false))));

        mockMvc.perform(get("/export").param("name", "orders").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"orders-")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"body\"")));
    }

    @Test
    @DisplayName("JSON export is served as JSON")
    void exportsJson() throws Exception
    {
        given(browseService.pageForExport(anyString(), any(), anyInt(), anyInt()))
                .willReturn(new MessagePage("orders", "", 1, 5000, 0, List.of()));

        mockMvc.perform(get("/export").param("name", "orders").param("format", "json").header("Host", "localhost"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    @DisplayName("a non-loopback Host header is refused on export too")
    void rejectsNonLoopbackHost() throws Exception
    {
        mockMvc.perform(get("/export").param("name", "orders").header("Host", "evil.example.com"))
                .andExpect(status().isForbidden());
    }
}
