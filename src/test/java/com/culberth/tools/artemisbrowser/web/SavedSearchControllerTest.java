package com.culberth.tools.artemisbrowser.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.culberth.tools.artemisbrowser.broker.AddressDirectory;
import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.MessageSearchService;
import com.culberth.tools.artemisbrowser.broker.QueueBrowseService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.broker.QueueStats;
import com.culberth.tools.artemisbrowser.filter.SavedSearch;
import com.culberth.tools.artemisbrowser.filter.SavedSearchException;
import com.culberth.tools.artemisbrowser.filter.SavedSearchStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SavedSearchController.class)
class SavedSearchControllerTest
{

    private static final Instant WHEN = Instant.parse("2026-09-30T12:00:00Z");
    private static final SavedSearch ON_QUEUE = new SavedSearch("q1", "stuck EU", "region = 'eu'",
            SavedSearch.Scope.QUEUE, "orders", false, "localhost:61616", WHEN, WHEN);
    private static final SavedSearch ON_ADDRESS = new SavedSearch("a1", "who got it", "orderId = 'A-1'",
            SavedSearch.Scope.ADDRESS, "events", false, "localhost:61616", WHEN, WHEN);
    private static final SavedSearch EVERYWHERE = new SavedSearch("e1", "urgent", "AMQPriority >= 7",
            SavedSearch.Scope.ALL_QUEUES, "", true, "otherhost:61616", WHEN, WHEN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SavedSearchStore store;

    @MockitoBean
    private BrokerSession brokerSession;

    @MockitoBean
    private QueueDirectory queueDirectory;

    @MockitoBean
    private AddressDirectory addressDirectory;

    @MockitoBean
    private MessageSearchService searchService;

    @MockitoBean
    private QueueBrowseService browseService;

    @BeforeEach
    void connected()
    {
        given(brokerSession.isConnected()).willReturn(true);
        given(brokerSession.info()).willReturn(new ConnectionInfo("localhost", 61616, "artemis"));
        given(store.file()).willReturn(Path.of("saved-searches.json"));
        given(store.maxEntries()).willReturn(200);
        given(store.all()).willReturn(new SavedSearchStore.Listing(List.of(ON_QUEUE, EVERYWHERE), null, true));
        given(store.find("q1")).willReturn(ON_QUEUE);
        given(store.find("a1")).willReturn(ON_ADDRESS);
        given(store.find("e1")).willReturn(EVERYWHERE);
    }

    @Test
    @DisplayName("the list shows every saved value and where they are kept")
    void listsEverything() throws Exception
    {
        mockMvc.perform(get("/saved").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("stuck EU")))
                .andExpect(content().string(containsString("region = &#39;eu&#39;")))
                .andExpect(content().string(containsString("queue orders")))
                .andExpect(content().string(containsString("every queue, internal ones included")))
                .andExpect(content().string(containsString("saved-searches.json")))
                .andExpect(content().string(containsString("/saved/q1/delete")));
    }

    @Test
    @DisplayName("the list and deleting work without a broker connection")
    void worksDisconnected() throws Exception
    {
        given(brokerSession.isConnected()).willReturn(false);
        mockMvc.perform(get("/saved").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("stuck EU")));
        given(store.delete("q1")).willReturn(true);
        mockMvc.perform(post("/saved/q1/delete").header("Host", "localhost")).andExpect(redirectedUrl("/saved"))
                .andExpect(flash().attribute("notice", "Deleted 'stuck EU'."));
    }

    @Test
    @DisplayName("a file that could not be read is reported on the list")
    void unreadableFile() throws Exception
    {
        given(store.all()).willReturn(new SavedSearchStore.Listing(List.of(), "Could not read it", false));
        mockMvc.perform(get("/saved").header("Host", "localhost"))
                .andExpect(content().string(containsString("Could not read it")));
    }

    @Test
    @DisplayName("saving records the broker's host and port, never the user")
    void saveRecordsBroker() throws Exception
    {
        given(store.create(anyString(), anyString(), any(), anyString(), anyBoolean(), anyString()))
                .willReturn(ON_QUEUE);
        mockMvc.perform(post("/saved").header("Host", "localhost").param("name", "stuck EU")
                .param("filter", "region = 'eu'").param("scope", "QUEUE").param("target", "orders"))
                .andExpect(redirectedUrl("/saved")).andExpect(flash().attribute("notice", "Saved 'stuck EU'."));
        verify(store).create("stuck EU", "region = 'eu'", SavedSearch.Scope.QUEUE, "orders", false, "localhost:61616");
    }

    @Test
    @DisplayName("a refused save says why, and an unknown scope saves nothing")
    void refusedSave() throws Exception
    {
        given(store.create(anyString(), anyString(), any(), anyString(), anyBoolean(), anyString()))
                .willThrow(new SavedSearchException("There is already a saved search called 'x'."));
        mockMvc.perform(post("/saved").header("Host", "localhost").param("name", "x").param("filter", "a = 1"))
                .andExpect(flash().attribute("problem", "Not saved: There is already a saved search called 'x'."));

        mockMvc.perform(post("/saved").header("Host", "localhost").param("name", "y").param("filter", "a = 1")
                .param("scope", "EVERYWHERE")).andExpect(flash().attribute("problem", containsString("Unknown scope")));
        verify(store, never()).create(eq("y"), anyString(), any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("rename passes the new name through, and reports a refusal")
    void rename() throws Exception
    {
        given(store.rename("q1", "EU backlog")).willReturn(ON_QUEUE.withName("EU backlog", WHEN));
        mockMvc.perform(post("/saved/q1/rename").header("Host", "localhost").param("name", "EU backlog"))
                .andExpect(flash().attribute("notice", "Renamed to 'EU backlog'."));
        given(store.rename("q1", "")).willThrow(new SavedSearchException("A saved search needs a name."));
        mockMvc.perform(post("/saved/q1/rename").header("Host", "localhost").param("name", ""))
                .andExpect(flash().attribute("problem", "Not renamed: A saved search needs a name."));
    }

    @Test
    @DisplayName("opening one shows broker and scope and a Run button, and runs nothing")
    void openShowsBeforeRunning() throws Exception
    {
        given(queueDirectory.stats("orders"))
                .willReturn(new QueueStats("orders", "orders", "ANYCAST", 0, 0, 0, 0, 0, 0, true, false));
        mockMvc.perform(get("/saved/q1").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("artemis@localhost:61616")))
                .andExpect(content().string(containsString("Queue orders is on this broker.")))
                .andExpect(content().string(containsString("action=\"/queues\"")))
                .andExpect(content().string(containsString("value=\"region = &#39;eu&#39;\"")))
                .andExpect(content().string(containsString(">Run<")));
        verifyNoInteractions(searchService, browseService);
    }

    @Test
    @DisplayName("a queue that has gone is explained, with no Run, rather than searched for zero matches")
    void missingQueue() throws Exception
    {
        given(queueDirectory.stats("orders")).willReturn(null);
        mockMvc.perform(get("/saved/q1").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("This broker has no queue named orders")))
                .andExpect(content().string(not(containsString(">Run<"))))
                .andExpect(content().string(containsString("Search every queue for it instead")));
    }

    @Test
    @DisplayName("an address scope checks the address and runs through the address page's find")
    void addressScope() throws Exception
    {
        given(addressDirectory.find("events")).willReturn(null);
        mockMvc.perform(get("/saved/a1").header("Host", "localhost"))
                .andExpect(content().string(containsString("This broker has no address named events")));
    }

    @Test
    @DisplayName("a scope that could not be checked still offers Run, and says it could not check")
    void uncheckedScope() throws Exception
    {
        given(queueDirectory.stats("orders")).willThrow(new BrokerException("timed out"));
        mockMvc.perform(get("/saved/q1").header("Host", "localhost"))
                .andExpect(content().string(containsString("Could not check whether it exists: timed out")))
                .andExpect(content().string(containsString(">Run<")));
    }

    @Test
    @DisplayName("a search saved on another broker says so before it runs")
    void otherBroker() throws Exception
    {
        mockMvc.perform(get("/saved/e1").header("Host", "localhost"))
                .andExpect(content().string(containsString("Saved while connected to otherhost:61616")))
                .andExpect(content().string(containsString("action=\"/search\"")))
                .andExpect(content().string(containsString("name=\"internal\" value=\"true\"")));
    }

    @Test
    @DisplayName("not connected: shown, not run, with a way to connect")
    void notConnected() throws Exception
    {
        given(brokerSession.isConnected()).willReturn(false);
        mockMvc.perform(get("/saved/q1").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("this session is not connected")))
                .andExpect(content().string(not(containsString(">Run<"))));
        verifyNoInteractions(queueDirectory);
    }

    @Test
    @DisplayName("an unknown id is explained")
    void unknownId() throws Exception
    {
        mockMvc.perform(get("/saved/nope").header("Host", "localhost"))
                .andExpect(content().string(containsString("There is no saved search with that id")));
    }
}
