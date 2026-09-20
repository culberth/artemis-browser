package com.culberth.tools.artemisbrowser.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.culberth.tools.artemisbrowser.broker.AddressDirectory;
import com.culberth.tools.artemisbrowser.broker.BrokerHealth;
import com.culberth.tools.artemisbrowser.broker.BrokerInfoService;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.StuckDiagnosisService;
import java.util.List;
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
 * at all.
 */
@WebMvcTest(BrokerController.class)
@WithMockUser
class PageRenderingTest
{

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
    }

    @Test
    @DisplayName("the broker page renders, fragment arguments and all")
    void rendersTheBrokerPage() throws Exception
    {
        mockMvc.perform(get("/broker").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2.42.0")))
                // The refresh control comes from the shared fragment, which is what broke.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Auto-refresh")));
    }

    @Test
    @DisplayName("the broker page still renders when the broker cannot be read")
    void rendersTheBrokerPageOnError()
    {
        given(brokerInfo.health()).willThrow(new com.culberth.tools.artemisbrowser.broker.BrokerException("nope"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> mockMvc.perform(get("/broker").header("Host", "localhost")).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("the addresses page renders")
    void rendersTheAddressesPage() throws Exception
    {
        mockMvc.perform(get("/addresses").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Addresses")));
    }
}
