package com.culberth.tools.artemisbrowser.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.culberth.tools.artemisbrowser.broker.AddressDirectory;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The default local run: bound to loopback, no login configured, no sign-in.
 *
 * <p>
 * Phase 7 added the login for running somewhere other than localhost, and the README said the loopback default still
 * needed none — but the chain demanded a sign-in everywhere, and with no account configured nobody could give one, so a
 * plain {@code mvn spring-boot:run} opened on a form that could not be passed. These start the whole context with the
 * shipped defaults ({@code server.address=127.0.0.1}, blank {@code artemis.auth.*}) and check the two things that must
 * stay true without a login: the host check, and CSRF.
 */
@SpringBootTest(properties =
{ "server.address=127.0.0.1", "artemis.auth.username=", "artemis.auth.password-hash="
})
@AutoConfigureMockMvc
class LocalWithoutLoginTest
{

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrokerSession brokerSession;

    @MockitoBean
    private AddressDirectory addressDirectory;

    @Test
    @DisplayName("pages open without signing in")
    void pagesOpenWithoutSigningIn() throws Exception
    {
        mockMvc.perform(get("/").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Connect to a broker")));
    }

    @Test
    @DisplayName("the sign-in page sends you to the app, since there is nothing to sign in to")
    void thereIsNothingToSignInTo() throws Exception
    {
        mockMvc.perform(get("/login").header("Host", "localhost")).andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("the nav offers no sign-out, since nobody is signed in")
    void theNavOffersNoSignOut() throws Exception
    {
        given(brokerSession.isConnected()).willReturn(true);
        given(brokerSession.info()).willReturn(new ConnectionInfo("localhost", 61616, "artemis"));
        given(addressDirectory.overview()).willReturn(List.of());

        mockMvc.perform(get("/addresses").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Addresses")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Sign out"))));
    }

    @Test
    @DisplayName("the host check still refuses a non-loopback Host, which is what stops DNS rebinding")
    void stillRefusesAForeignHost() throws Exception
    {
        mockMvc.perform(get("/").header("Host", "evil.example.com")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a POST without a CSRF token is still refused")
    void stillRequiresCsrf() throws Exception
    {
        mockMvc.perform(post("/disconnect").header("Host", "localhost")).andExpect(status().isForbidden());
        mockMvc.perform(post("/disconnect").header("Host", "localhost").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    /** The compare upload is a POST like any other: refused without the session's token, taken with it. */
    @Test
    @DisplayName("the compare upload needs the CSRF token, and the form carries it")
    void compareUploadNeedsItsToken() throws Exception
    {
        MockMultipartFile file = new MockMultipartFile("earlier", "a.json", "application/json", new byte[]
        { '{', '}'
        });
        mockMvc.perform(multipart("/compare").file(file).header("Host", "localhost")).andExpect(status().isForbidden());

        MockHttpSession session = new MockHttpSession();
        String form = mockMvc.perform(get("/compare").session(session).header("Host", "localhost")).andReturn()
                .getResponse().getContentAsString();
        Matcher token = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(form);
        assertTrue(token.find(), form);
        mockMvc.perform(multipart("/compare").file(file).param("_csrf", token.group(1)).session(session).header("Host",
                "localhost")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Choose two snapshot files")));
    }

    @Test
    @DisplayName("open only when both hold: no login, and bound to loopback")
    void opensOnlyOnLoopbackWithoutALogin()
    {
        assertTrue(SecurityConfig.openLocally("127.0.0.1", "", ""));
        assertTrue(SecurityConfig.openLocally("::1", null, null));
        assertFalse(SecurityConfig.openLocally("127.0.0.1", "me", "$2a$10$hash"), "a configured login is used");
        assertFalse(SecurityConfig.openLocally("0.0.0.0", "", ""), "every interface is not loopback");
        assertFalse(SecurityConfig.openLocally("", "", ""), "unset means every interface");
        assertFalse(SecurityConfig.openLocally("127.0.0.1.attacker.com", "", ""));
    }
}
