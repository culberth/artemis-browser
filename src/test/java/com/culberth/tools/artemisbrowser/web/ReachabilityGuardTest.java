package com.culberth.tools.artemisbrowser.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check that decides whether a deployment is allowed to exist.
 *
 * <p>
 * Every case here is a configuration someone could plausibly write while trying to get the tool onto a jump host, and
 * the two that fail are the ones that would otherwise work fine right up until they didn't.
 */
class ReachabilityGuardTest
{

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuv";

    @Test
    @DisplayName("loopback with no login is how it has always run, and stays allowed")
    void loopbackNeedsNothing()
    {
        assertDoesNotThrow(() -> guard("127.0.0.1", "", "", false, "").check());
        assertDoesNotThrow(() -> guard("localhost", "", "", false, "").check());
        assertDoesNotThrow(() -> guard("::1", "", "", false, "").check());
    }

    @Test
    @DisplayName("exposed with no login is refused before it can serve anything")
    void exposedWithoutALoginIsRefused()
    {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> guard("0.0.0.0", "", "", false, "").check());

        assertTrue(thrown.getMessage().contains("no login is configured"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("artemis.auth.username"), thrown.getMessage());
    }

    @Test
    @DisplayName("an unset bind address means every interface, and is treated as exposed")
    void anUnsetAddressCountsAsExposed()
    {
        // Spring Boot's own default, and the easiest way to be reachable without deciding to be.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> guard("", "", "", false, "").check());

        assertTrue(thrown.getMessage().contains("every interface"), thrown.getMessage());
    }

    @Test
    @DisplayName("exposed with a login but no TLS is refused, because that is the worse deployment")
    void exposedWithoutTlsIsRefused()
    {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> guard("0.0.0.0", "bo", HASH, false, "").check());

        assertTrue(thrown.getMessage().contains("TLS is not configured"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("cross the network in the clear"), thrown.getMessage());
    }

    @Test
    @DisplayName("exposed with a login and TLS is the deployment this was built for")
    void exposedWithBothIsAllowed()
    {
        assertDoesNotThrow(() -> guard("0.0.0.0", "bo", HASH, true, "").check());
        // A key store on its own counts: setting one and leaving server.ssl.enabled unset is the
        // usual way it is written, and Boot treats it as enabled too.
        assertDoesNotThrow(() -> guard("10.0.0.5", "bo", HASH, false, "file:/etc/artemis.p12").check());
    }

    @Test
    @DisplayName("half a login is not a login")
    void aPartialLoginDoesNotCount()
    {
        assertThrows(IllegalStateException.class, () -> guard("0.0.0.0", "bo", "", true, "").check());
        assertThrows(IllegalStateException.class, () -> guard("0.0.0.0", "", HASH, true, "").check());
    }

    private ReachabilityGuard guard(String address, String username, String hash, boolean sslEnabled, String keyStore)
    {
        return new ReachabilityGuard(address, username, hash, sslEnabled, keyStore);
    }
}
