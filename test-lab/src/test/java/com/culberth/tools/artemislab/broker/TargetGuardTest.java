package com.culberth.tools.artemislab.broker;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TargetGuardTest
{

    @Test
    @DisplayName("A connection reaching another broker is closed and refused before the caller can send on it")
    void refusesAnotherBroker() throws Exception
    {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(factory.createConnection("u", "p")).thenReturn(connection);
        TargetGuard guard = new TargetGuard(factory, "u", "p", "lab-node", c -> "cluster-node");

        assertThrows(TargetMismatchException.class, guard::open);

        verify(connection).close();
        verify(connection, never()).createSession(false, 1);
    }

    @Test
    @DisplayName("A connection reaching the owned broker is handed over, started")
    void acceptsTheOwnedBroker() throws Exception
    {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(factory.createConnection("u", "p")).thenReturn(connection);
        TargetGuard guard = new TargetGuard(factory, "u", "p", "lab-node", c -> "lab-node");

        assertSame(connection, guard.open());
        verify(connection).start();
        verify(connection, never()).close();
    }

    @Test
    @DisplayName("A failed identity read closes the connection and fails, rather than trusting it")
    void failedReadIsNotTrusted() throws Exception
    {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(factory.createConnection("u", "p")).thenReturn(connection);
        TargetGuard guard = new TargetGuard(factory, "u", "p", "lab-node", c ->
        {
            throw new jakarta.jms.JMSException("no reply");
        });

        assertThrows(jakarta.jms.JMSException.class, guard::open);
        verify(connection).close();
    }
}
