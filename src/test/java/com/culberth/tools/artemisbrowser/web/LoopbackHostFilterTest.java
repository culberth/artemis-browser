package com.culberth.tools.artemisbrowser.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Host check is the only thing standing between a malicious page and this app's broker connection, so the rejection
 * cases matter more than the acceptance ones.
 */
class LoopbackHostFilterTest
{

    @ParameterizedTest
    @ValueSource(strings =
    { "localhost", "localhost:8080", "LOCALHOST:8080", "127.0.0.1", "127.0.0.1:8080", "127.1.2.3:8080", "[::1]",
            "[::1]:8080", "::1"
    })
    @DisplayName("accepts loopback hosts, with or without a port, in any case")
    void acceptsLoopbackHosts(String host)
    {
        assertTrue(LoopbackHostFilter.isLoopbackHost(host), host);
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
        assertFalse(LoopbackHostFilter.isLoopbackHost(host), host);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings =
    { "   "
    })
    @DisplayName("rejects a missing or blank Host header rather than defaulting to allow")
    void rejectsMissingHost(String host)
    {
        assertFalse(LoopbackHostFilter.isLoopbackHost(host), String.valueOf(host));
    }
}
