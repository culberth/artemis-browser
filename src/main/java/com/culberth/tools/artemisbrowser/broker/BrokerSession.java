package com.culberth.tools.artemisbrowser.broker;

import jakarta.annotation.PreDestroy;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;
import jakarta.jms.Session;
import org.apache.activemq.artemis.api.config.ActiveMQDefaultConfiguration;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/**
 * One user's live connection to a broker, held for the life of their HTTP session.
 *
 * <p>
 * The password is used to open the connection and then dropped — only {@link ConnectionInfo} (host, port, username) is
 * retained, so a session hijack or a heap dump does not hand over broker credentials. Reconnecting requires retyping
 * the password, which is the intended trade.
 *
 * <p>
 * Everything is synchronized: a JMS {@link Session} is not thread-safe, and a browser with two tabs open will happily
 * make concurrent requests.
 */
@Component
@SessionScope
public class BrokerSession implements AutoCloseable
{

    private final long managementTimeoutMillis;
    private final int connectionTimeoutMillis;

    private ActiveMQConnectionFactory factory;
    private Connection connection;
    private Session session;
    private ManagementChannel management;
    private ConnectionInfo info;

    public BrokerSession(@Value("${artemis.management-timeout-ms:10000}") long managementTimeoutMillis,
            @Value("${artemis.connection-timeout-ms:10000}") int connectionTimeoutMillis)
    {
        this.managementTimeoutMillis = managementTimeoutMillis;
        this.connectionTimeoutMillis = connectionTimeoutMillis;
    }

    public synchronized void connect(BrokerCredentials credentials)
    {
        close();
        try
        {
            factory = new ActiveMQConnectionFactory(credentials.brokerUrl());
            factory.setCallTimeout(connectionTimeoutMillis);
            factory.setConnectionTTL(-1);
            // One attempt, no silent retry loop: a wrong host should fail the form fast rather
            // than hang the request thread while the client retries in the background.
            factory.setInitialConnectAttempts(1);
            factory.setReconnectAttempts(0);

            connection = factory.createConnection(credentials.username(), credentials.password());
            // Required before a consumer — including the management reply consumer — will receive.
            connection.start();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            management = new ManagementChannel(session,
                    ActiveMQDefaultConfiguration.getDefaultManagementAddress().toString(), managementTimeoutMillis);
            info = credentials.toInfo();
        }
        catch (JMSSecurityException e)
        {
            close();
            throw new BrokerException(
                    "The broker rejected those credentials for user '" + credentials.username() + "'.", e);
        }
        catch (JMSException e)
        {
            close();
            throw new BrokerException("Could not connect to " + credentials.brokerUrl() + ": " + rootMessage(e), e);
        }
        catch (RuntimeException e)
        {
            close();
            throw new BrokerException("Could not connect to " + credentials.brokerUrl() + ": " + rootMessage(e), e);
        }
    }

    public synchronized boolean isConnected()
    {
        return connection != null && session != null;
    }

    public synchronized ConnectionInfo info()
    {
        return info;
    }

    synchronized Session requireSession()
    {
        if (session == null)
        {
            throw new NotConnectedException();
        }
        return session;
    }

    synchronized ManagementChannel requireManagement()
    {
        if (management == null)
        {
            throw new NotConnectedException();
        }
        return management;
    }

    @Override
    @PreDestroy
    public synchronized void close()
    {
        if (management != null)
        {
            management.close();
            management = null;
        }
        closeQuietly(session);
        closeQuietly(connection);
        closeQuietly(factory);
        session = null;
        connection = null;
        factory = null;
        info = null;
    }

    private void closeQuietly(AutoCloseable closeable)
    {
        if (closeable == null)
        {
            return;
        }
        try
        {
            closeable.close();
        }
        catch (Exception ignored)
        {
            // Tearing down after a failure; the original failure is the one worth reporting.
        }
    }

    private String rootMessage(Throwable t)
    {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root)
        {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
