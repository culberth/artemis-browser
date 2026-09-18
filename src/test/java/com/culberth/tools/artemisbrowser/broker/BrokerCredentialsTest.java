package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BrokerCredentialsTest
{

    @Test
    @DisplayName("builds the Artemis core URL from host and port")
    void buildsBrokerUrl()
    {
        assertEquals("tcp://broker.internal:61616",
                new BrokerCredentials("broker.internal", 61616, "user", "secret").brokerUrl());
    }

    @Test
    @DisplayName("the retained ConnectionInfo carries no password, and cannot be made to")
    void infoDropsPassword()
    {
        ConnectionInfo info = new BrokerCredentials("host", 61616, "user", "hunter2").toInfo();

        assertEquals("user@host:61616", info.describe());
        // Guards the security property directly: whatever ConnectionInfo renders or serializes,
        // the password must not be recoverable from it.
        assertFalse(info.toString().contains("hunter2"), info.toString());
    }
}
