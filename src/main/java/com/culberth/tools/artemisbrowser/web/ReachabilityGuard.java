package com.culberth.tools.artemisbrowser.web;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start in a configuration that would put credentials on the network in the clear.
 *
 * <p>
 * Binding beyond loopback is now allowed — that was the point of Phase 7 — but only with the two things that make it
 * survivable. A login, so reaching the port is not the same as reading the broker. And TLS, because both passwords that
 * matter cross the wire: the one for this tool, and the broker's own, which the connect form asks for on every
 * connection. Authentication over cleartext HTTP would be worse than the loopback-only arrangement it replaced, since
 * it looks protected.
 *
 * <p>
 * Failing at startup rather than warning: a warning in a log is read after the incident, and this is exactly the
 * misconfiguration nobody notices while it works fine.
 */
@Component
public class ReachabilityGuard
{

    private final String bindAddress;
    private final boolean authConfigured;
    private final boolean tlsConfigured;

    public ReachabilityGuard(@Value("${server.address:}") String bindAddress,
            @Value("${artemis.auth.username:}") String username,
            @Value("${artemis.auth.password-hash:}") String passwordHash,
            @Value("${server.ssl.enabled:false}") boolean sslEnabled,
            @Value("${server.ssl.key-store:}") String keyStore)
    {
        this.bindAddress = bindAddress == null ? "" : bindAddress.trim();
        this.authConfigured = !username.isBlank() && !passwordHash.isBlank();
        this.tlsConfigured = sslEnabled || !keyStore.isBlank();
    }

    @PostConstruct
    void check()
    {
        if (loopbackOnly())
        {
            return;
        }
        if (!authConfigured)
        {
            throw new IllegalStateException(refusal("no login is configured")
                    + " Set artemis.auth.username and artemis.auth.password-hash, or bind to 127.0.0.1.");
        }
        if (!tlsConfigured)
        {
            throw new IllegalStateException(refusal("TLS is not configured")
                    + " Both this tool's password and the broker password would cross the network in the clear."
                    + " Set server.ssl.key-store, or bind to 127.0.0.1.");
        }
    }

    /**
     * An empty bind address means every interface, which is Spring Boot's default and the easiest way to end up exposed
     * without deciding to be.
     */
    private boolean loopbackOnly()
    {
        return !bindAddress.isEmpty() && AllowedHostFilter.isLoopbackHost(bindAddress);
    }

    private String refusal(String because)
    {
        return "artemis-browser is configured to listen on '"
                + (bindAddress.isEmpty() ? "every interface" : bindAddress) + "' but " + because + ".";
    }
}
