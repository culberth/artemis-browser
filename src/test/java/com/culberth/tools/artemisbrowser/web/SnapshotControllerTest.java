package com.culberth.tools.artemisbrowser.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.culberth.tools.artemisbrowser.broker.BrokerHealth;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.IncidentSnapshot;
import com.culberth.tools.artemisbrowser.broker.Reading;
import com.culberth.tools.artemisbrowser.broker.SnapshotService;
import com.culberth.tools.artemisbrowser.broker.Trends;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SnapshotController.class)
class SnapshotControllerTest
{

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrokerSession brokerSession;

    @MockitoBean
    private SnapshotService snapshotService;

    @BeforeEach
    void connected()
    {
        given(brokerSession.isConnected()).willReturn(true);
        ConnectionInfo connection = new ConnectionInfo("broker.example", 61616, "artemis");
        given(snapshotService.collect()).willReturn(new IncidentSnapshot(Instant.now(), Instant.now(), connection,
                BrokerHealth.of("2.55.0", "1h", "STARTED", "n", 1, 1, 1, 0, 0, 10, 90), Reading.of(List.of()),
                Reading.of(List.of()), Map.of(), 0, IncidentSnapshot.Listing.of(Reading.of(List.of()), 10),
                IncidentSnapshot.Listing.of(Reading.of(List.of()), 10),
                IncidentSnapshot.Listing.of(Reading.of(List.of()), 10),
                IncidentSnapshot.Listing.of(Reading.of(List.of()), 10), Trends.none(),
                Reading.notCollected("not in this test"), new IncidentSnapshot.Limits(10, 10, 15_000, 240, 500)));
    }

    @Test
    @DisplayName("the JSON snapshot downloads as an attachment named for the broker")
    void downloadsJson() throws Exception
    {
        mockMvc.perform(get("/snapshot").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=\"incident-broker.example-61616-\\d{8}-\\d{6}\\.json\"")))
                .andExpect(content().string(containsString("\"schemaVersion\"")));
    }

    @Test
    @DisplayName("the text summary downloads as plain text")
    void downloadsText() throws Exception
    {
        mockMvc.perform(get("/snapshot").param("format", "text").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/plain")))
                .andExpect(content().string(containsString("artemis-browser incident snapshot")));
    }

    @Test
    @DisplayName("without a connection there is nothing to collect: back to the connect form")
    void redirectsWhenDisconnected() throws Exception
    {
        given(brokerSession.isConnected()).willReturn(false);

        mockMvc.perform(get("/snapshot").header("Host", "localhost")).andExpect(redirectedUrl("/"));
        verify(snapshotService, never()).collect();
    }

    @Test
    @DisplayName("a host that could break out of the filename is made safe")
    void safeFileName()
    {
        String name = SnapshotController.fileName(new ConnectionInfo("evil\"; x=/../", 1, "u"), "json");
        org.junit.jupiter.api.Assertions.assertTrue(name.matches("incident-[A-Za-z0-9._-]+\\.json"), name);
    }
}
