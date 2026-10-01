package com.culberth.tools.artemislab.broker;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import java.time.Duration;

/**
 * Hands out connections only to the broker the lab owns.
 *
 * <p>
 * Every connection is checked on its own, before the caller gets it: a factory's second connection can land on a
 * different broker than its first (2.57.0 follows the announced topology unless
 * {@code useTopologyForLoadBalancing=false}, which on this machine meant the kind cluster's broker). A node id that
 * differs from the recorded one is refused, never adopted — a replaced broker is a new provisioning, not a silent
 * continuation.
 */
public final class TargetGuard
{

    /** Reads a connection's broker node id. The real one asks management; tests substitute their own. */
    @FunctionalInterface
    public interface IdentityReader
    {
        String nodeId(Connection connection) throws JMSException;
    }

    private final ConnectionFactory factory;
    private final String user;
    private final String password;
    private final String expectedNodeId;
    private final IdentityReader reader;

    public TargetGuard(ConnectionFactory factory, String user, String password, String expectedNodeId,
            IdentityReader reader)
    {
        this.factory = factory;
        this.user = user;
        this.password = password;
        this.expectedNodeId = expectedNodeId;
        this.reader = reader;
    }

    public static IdentityReader managementReader(Duration timeout)
    {
        return connection ->
        {
            try (ManagementClient management = new ManagementClient(connection, timeout))
            {
                return management.nodeId();
            }
        };
    }

    public String expectedNodeId()
    {
        return expectedNodeId;
    }

    /** A started connection verified to reach the owned broker; the caller closes it. */
    public Connection open() throws JMSException
    {
        return open(null);
    }

    /**
     * As {@link #open()}, with a JMS client id set first — it has to be, since JMS refuses a client id on a connection
     * that has been used, and verifying it uses it.
     */
    public Connection open(String clientId) throws JMSException
    {
        Connection connection = factory.createConnection(user, password);
        try
        {
            if (clientId != null && !clientId.isBlank())
            {
                connection.setClientID(clientId);
            }
            connection.start();
            String actual = reader.nodeId(connection);
            if (!expectedNodeId.equals(actual))
            {
                throw new TargetMismatchException(expectedNodeId, actual);
            }
            return connection;
        }
        catch (JMSException | RuntimeException e)
        {
            try
            {
                connection.close();
            }
            catch (JMSException suppressed)
            {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }
}
