package com.culberth.tools.artemislab.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.culberth.tools.artemislab.broker.BrokerLauncher;
import com.culberth.tools.artemislab.run.RunManifest;
import com.culberth.tools.artemislab.run.RunStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole application, with the Docker launcher mocked: pages render, and the web defenses — Host check, CSRF, POST
 * only — hold in the real filter chain rather than a slice that leaves security out.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LabPagesTest
{

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("lab.data-dir", dataDir::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    RunStore store;

    @MockitoBean
    BrokerLauncher launcher;

    @BeforeEach
    void noLeftovers()
    {
        given(launcher.leftovers()).willReturn(List.of());
    }

    @Test
    @DisplayName("Every page renders, offering only the pinned images")
    void pagesRender() throws Exception
    {
        mvc.perform(get("/").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("apache/artemis:2.55.0-alpine")))
                .andExpect(content().string(containsString("apache/artemis:2.57.0-alpine")))
                .andExpect(content().string(containsString("No lab broker is running")));
        mvc.perform(get("/catalog").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("LAB-SMOKE")))
                .andExpect(content().string(containsString("external harness")))
                .andExpect(content().string(containsString("INVESTIGATION-CONTENT")))
                .andExpect(content().string(not(containsString("Cases without a recipe yet"))));
    }

    @Test
    @DisplayName("A run page renders its manifest, and the download carries it with no password")
    void runPageAndManifest() throws Exception
    {
        store.create(RunManifest.open("rpage", "rev", Instant.now(), "UTC", "", new RunManifest.BrokerRef("b1",
                "apache/artemis:2.55.0-alpine", "2.55.0", "node-1", "127.0.0.1:62616")));
        for (String recipe : List.of("INCIDENT", "INVESTIGATION"))
            store.update("rpage", r -> r.withAction(com.culberth.tools.artemislab.run.ActionRecord.now("job", recipe,
                    com.culberth.tools.artemislab.run.ActionRecord.Outcome.SUCCEEDED, "prepared")));

        mvc.perform(get("/runs/rpage").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(containsString("BODIES")))
                .andExpect(content().string(containsString("Roll back prepared branch")))
                .andExpect(content().string(containsString("Add ten messages")))
                .andExpect(content().string(containsString("node-1")));
        mvc.perform(get("/runs/rpage/manifest.json").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("lab-run-rpage.json")))
                .andExpect(content().string(containsString("\"nodeId\"")))
                .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    @DisplayName("A non-loopback Host is refused before anything renders")
    void hostChecked() throws Exception
    {
        mvc.perform(get("/").header("Host", "attacker.example")).andExpect(status().isForbidden());
        mvc.perform(get("/").header("Host", "127.0.0.1.attacker.example")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A POST without a CSRF token changes nothing")
    void csrfRequired() throws Exception
    {
        mvc.perform(post("/broker/provision").header("Host", "localhost").param("image", "apache/artemis:2.55.0-alpine")
                .param("token", "t1")).andExpect(status().isForbidden());
        verify(launcher, never()).launch(anyString(), anyString(), any(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("An image outside the pinned matrix is refused, and nothing is launched")
    void unsupportedImageRefused() throws Exception
    {
        mvc.perform(post("/broker/provision").header("Host", "localhost").with(csrf())
                .param("image", "apache/artemis:latest").param("token", "t2")).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("Not a supported image")));
        verify(launcher, never()).launch(anyString(), anyString(), any(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("A run cannot start without a lab broker")
    void runNeedsBroker() throws Exception
    {
        mvc.perform(post("/runs").header("Host", "localhost").with(csrf())).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("Provision a lab broker")));
    }
}
