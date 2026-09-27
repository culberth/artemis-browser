package com.culberth.tools.artemisbrowser.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * That the app is actually protected, which a controller slice does not tell you.
 *
 * <p>
 * The whole application context is started here on purpose. The sliced controller tests kept passing when security was
 * first switched on, because a slice does not necessarily apply the real filter chain — so green tests were not
 * evidence of anything. These run against the chain the application itself builds.
 */
@SpringBootTest(properties =
{ "artemis.auth.username=tester",
        // bcrypt of "correct-horse"
        "artemis.auth.password-hash=$2a$10$f8/orV9lQp75eS5gnQUXeOFlMtJNzZ1egoONu7Rik1pltb/uUXgHO"
})
@AutoConfigureMockMvc
class SecurityConfigTest
{

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrokerSession brokerSession;

    @MockitoBean
    private com.culberth.tools.artemisbrowser.broker.AddressDirectory addressDirectory;

    @Test
    @DisplayName("every page needs a signed-in session")
    void pagesRequireSigningIn() throws Exception
    {
        for (String path : new String[]
        { "/", "/overview", "/queues", "/message", "/search", "/export", "/broker", "/addresses"
        })
        {
            mockMvc.perform(get(path).header("Host", "localhost")).andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        }
    }

    @Test
    @DisplayName("the sign-in page and its stylesheet are reachable without signing in")
    void theWayInIsReachable() throws Exception
    {
        mockMvc.perform(get("/login").header("Host", "localhost")).andExpect(status().isOk());
        mockMvc.perform(get("/app.css").header("Host", "localhost")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the configured password is accepted and a wrong one is not")
    void signsInWithTheConfiguredPassword() throws Exception
    {
        // Posted by hand rather than with formLogin(), which sends no Host header and is therefore
        // turned away by AllowedHostFilter before it reaches the login — the filter doing its job.
        mockMvc.perform(signIn("tester", "correct-horse")).andExpect(authenticated());
        mockMvc.perform(signIn("tester", "wrong")).andExpect(unauthenticated());
        mockMvc.perform(signIn("nobody", "correct-horse")).andExpect(unauthenticated());
    }

    @Test
    @DisplayName("signing out ends the session")
    void signsOut() throws Exception
    {
        mockMvc.perform(post("/logout").header("Host", "localhost").with(csrf())).andExpect(unauthenticated());
    }

    @Test
    @DisplayName("a POST without a CSRF token is refused even when signed in")
    void refusesAPostWithoutACsrfToken() throws Exception
    {
        // The forms carry a token; something else posting on a signed-in user's behalf does not.
        // Signed in for real: @WithMockUser does not reach this full-context MockMvc, and with it
        // this test passed on a redirect to /login — which is any redirect, not the signed-in one.
        MockHttpSession session = signedIn();
        mockMvc.perform(post("/disconnect").header("Host", "localhost").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/disconnect").header("Host", "localhost").session(session).with(csrf()))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("a signed-in user is named in the nav and offered a sign-out")
    void offersSignOutWhenSignedIn() throws Exception
    {
        org.mockito.BDDMockito.given(brokerSession.isConnected()).willReturn(true);
        org.mockito.BDDMockito.given(brokerSession.info())
                .willReturn(new com.culberth.tools.artemisbrowser.broker.ConnectionInfo("localhost", 61616, "artemis"));
        org.mockito.BDDMockito.given(addressDirectory.overview()).willReturn(java.util.List.of());

        mockMvc.perform(get("/addresses").header("Host", "localhost").session(signedIn())).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Signed in as tester")));
    }

    /** A session signed in with the configured password, through the real login form. */
    private MockHttpSession signedIn() throws Exception
    {
        MvcResult result = mockMvc.perform(signIn("tester", "correct-horse")).andExpect(authenticated()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signIn(String user,
            String password)
    {
        return post("/login").header("Host", "localhost").with(csrf()).param("username", user).param("password",
                password);
    }

    @Test
    @DisplayName("the host check runs before authentication, not after")
    void refusesABadHostBeforeAskingWhoYouAre() throws Exception
    {
        // Ahead of the security chain on purpose: a rebinding attack rides a session that is
        // already signed in, so checking the host afterwards would be checking it too late. A
        // redirect to /login here would mean the order is wrong.
        mockMvc.perform(get("/overview").header("Host", "evil.example.com")).andExpect(status().isForbidden());
    }
}
