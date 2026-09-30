package com.culberth.tools.artemisbrowser.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.compare.SnapshotJson;
import com.culberth.tools.artemisbrowser.compare.SnapshotReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The compare page renders every state — the empty form, a full comparison, a refusal — and never reaches for a broker:
 * no management channel is asked for, connected or not.
 */
@WebMvcTest(CompareController.class)
@Import(SnapshotReader.class)
class CompareControllerTest
{

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrokerSession brokerSession;

    @BeforeEach
    void offline()
    {
        given(brokerSession.isConnected()).willReturn(false);
    }

    @Test
    @DisplayName("the form renders with no broker connection, with a way back to connect")
    void formOffline() throws Exception
    {
        mockMvc.perform(get("/compare").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Compare two incident snapshots")))
                .andExpect(content().string(containsString("comparing needs no broker")))
                .andExpect(content().string(containsString("enctype=\"multipart/form-data\"")));
        verify(brokerSession).isConnected();
        verifyNoMoreInteractions(brokerSession);
    }

    @Test
    @DisplayName("connected, the page has the usual nav, and still asks the session for nothing but who it is")
    void formConnected() throws Exception
    {
        given(brokerSession.isConnected()).willReturn(true);
        given(brokerSession.info()).willReturn(new ConnectionInfo("localhost", 61616, "artemis"));

        mockMvc.perform(get("/compare").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Disconnect")))
                .andExpect(content().string(containsString("tab current")));
        verify(brokerSession).isConnected();
        verify(brokerSession).info();
        verifyNoMoreInteractions(brokerSession);
    }

    @Test
    @DisplayName("two snapshots render a comparison: identity, continuity, changes with their evidence, coverage")
    void comparesTwoFiles() throws Exception
    {
        byte[] before = before();
        byte[] after = after();

        String page = mockMvc
                .perform(multipart("/compare")
                        .file(new MockMultipartFile("earlier", "later.json", "application/json", after))
                        .file(new MockMultipartFile("later", "../../etc/earlier.json", "application/json", before))
                        .with(csrf()).header("Host", "localhost"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertAll(() -> contains(page, "Same broker."),
                () -> contains(page, "put in order"), () -> contains(page, "restarted"),
                () -> contains(page, "not traffic"), () -> contains(page, "10 → 250 messages (+240)"),
                () -> contains(page, "queue listing, read 08:00:01 → 08:40:01 UTC"),
                () -> contains(page, "no longer reported"), () -> contains(page, "Nothing is reading &#39;orders&#39;"),
                () -> contains(page, "Not collected: consumers: not permitted for this user"),
                () -> contains(page, "Not compared: the later snapshot could not read it"),
                () -> contains(page, "&#39;earlier.json&#39;"), () -> notContains(page, "etc/earlier"),
                () -> notContains(page, "<script>alert"));
        verify(brokerSession).isConnected();
        verifyNoMoreInteractions(brokerSession);
    }

    @Test
    @DisplayName("a refusal is shown as the reason, and nothing is compared")
    void refusal() throws Exception
    {
        mockMvc.perform(multipart("/compare")
                .file(new MockMultipartFile("earlier", "a.txt", "text/plain",
                        "artemis-browser incident snapshot (schema 1)".getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("later", "b.json", "application/json", after())).with(csrf())
                .header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("&#39;a.txt&#39; is not a JSON file")))
                .andExpect(content().string(not(containsString("What was compared"))));
    }

    @Test
    @DisplayName("one file is not a comparison")
    void needsTwo() throws Exception
    {
        mockMvc.perform(
                multipart("/compare").file(new MockMultipartFile("earlier", "a.json", "application/json", after()))
                        .with(csrf()).header("Host", "localhost"))
                .andExpect(content().string(containsString("Choose two snapshot files")));
    }

    @Test
    @DisplayName("an uploaded name is shown without its path or control characters, and shortened")
    void labels()
    {
        assertEquals("'b.json'", CompareController.label("C:\\x\\..\\b.json", "f"));
        assertEquals("'ab.json'", CompareController.label("a\u0007b.json", "f"));
        assertEquals("f", CompareController.label("dir/", "f"));
        assertEquals(123, CompareController.label("x".repeat(500), "f").length());
    }

    /** Up an hour, a queue nothing reads, and diagnose saying so. */
    private static byte[] before()
    {
        return SnapshotJson.at("2026-09-30T08:00:00Z").uptime(3_600_000).queue("orders", 7, 10, 100, 90)
                .finding("stuck", "Nothing is reading 'orders'", "orders").bytes();
    }

    /**
     * Forty minutes on, after a restart: the queue deeper, a queue with a hostile name, and a consumer listing the user
     * may not read.
     */
    private static byte[] after()
    {
        return SnapshotJson.at("2026-09-30T08:40:00Z").uptime(600_000).queue("orders", 7, 250, 30, 0)
                .queue("<script>alert(1)</script>", 8, 0, 0, 0).denied("consumers").bytes();
    }

    private static void contains(String page, String text)
    {
        org.junit.jupiter.api.Assertions.assertTrue(page.contains(text), "missing: " + text);
    }

    private static void notContains(String page, String text)
    {
        org.junit.jupiter.api.Assertions.assertFalse(page.contains(text), "present: " + text);
    }
}
