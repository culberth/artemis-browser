package com.culberth.tools.artemisbrowser.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Host check is the only thing standing between a malicious page and this app's broker connection, so the rejection
 * cases matter more than the acceptance ones.
 */
class AllowedHostFilterTest
{

    @ParameterizedTest
    @ValueSource(strings =
    { "localhost", "localhost:8080", "LOCALHOST:8080", "127.0.0.1", "127.0.0.1:8080", "127.1.2.3:8080", "[::1]",
            "[::1]:8080", "::1"
    })
    @DisplayName("accepts loopback hosts, with or without a port, in any case")
    void acceptsLoopbackHosts(String host)
    {
        assertTrue(AllowedHostFilter.isLoopbackHost(host), host);
    }

    @ParameterizedTest
    @ValueSource(strings =
    { "example.com", "example.com:8080", "10.0.0.5:8080", "192.168.1.10",
            // The rebinding shapes: a hostname that merely looks loopback-ish, or embeds it.
            "localhost.example.com", "notlocalhost", "127.0.0.1.example.com", "evil.com:8080",
            // Not 127.x — the prefix check must not match on a bare digit run.
            "1270.0.0.1", "0.0.0.0"
    })
    @DisplayName("rejects anything that is not actually loopback")
    void rejectsNonLoopbackHosts(String host)
    {
        assertFalse(AllowedHostFilter.isLoopbackHost(host), host);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings =
    { "   "
    })
    @DisplayName("rejects a missing or blank Host header rather than defaulting to allow")
    void rejectsMissingHost(String host)
    {
        assertFalse(AllowedHostFilter.isLoopbackHost(host), String.valueOf(host));
    }

    @Test
    @DisplayName("a named host is served, and anything else still is not")
    void servesOnlyTheHostsNamed()
    {
        AllowedHostFilter filter = new AllowedHostFilter(List.of("jump.example.com", "ARTEMIS.internal"));

        assertTrue(filter.isAllowed("jump.example.com"));
        assertTrue(filter.isAllowed("jump.example.com:8443"), "a port must not change the answer");
        assertTrue(filter.isAllowed("ARTEMIS.INTERNAL"), "matching is case-insensitive both ways");
        assertFalse(filter.isAllowed("evil.example.com"));
        assertFalse(filter.isAllowed("jump.example.com.evil.example.com"), "a suffix is not a match");
    }

    @Test
    @DisplayName("loopback is served whether or not anything was named")
    void loopbackIsAlwaysAllowed()
    {
        assertTrue(new AllowedHostFilter(List.of()).isAllowed("localhost:8080"));
        assertTrue(new AllowedHostFilter(List.of("jump.example.com")).isAllowed("127.0.0.1"));
    }

    @Test
    @DisplayName("naming nothing leaves it loopback-only, which is how it shipped for six phases")
    void namingNothingIsLoopbackOnly()
    {
        AllowedHostFilter filter = new AllowedHostFilter(List.of());

        assertFalse(filter.isAllowed("jump.example.com"));
        assertFalse(filter.isAllowed(null));
    }
}
