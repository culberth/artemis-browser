package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.jms.Connection;
import jakarta.jms.Session;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The request/reply plumbing against a real broker, which is the only place it can honestly be checked: every service
 * in this package depends on it, and a mock cannot be told to refuse an operation the way a broker does.
 */
class ManagementChannelIT
{

    private static BrokerSession brokerSession;

    @BeforeAll
    static void connect() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
    }

    @AfterAll
    static void disconnect()
    {
        if (brokerSession != null)
        {
            brokerSession.close();
        }
    }

    @Test
    @DisplayName("a management call goes out and the answer comes back")
    void completesARoundTrip()
    {
        Object result = brokerSession.requireManagement().invoke(ResourceNames.BROKER, "listQueues",
                "{\"field\":\"\",\"operation\":\"\",\"value\":\"\"}", 1, 200);

        assertNotNull(result);
        assertTrue(result.toString().contains(ArtemisBrokerSupport.TEXT_QUEUE), result.toString());
    }

    @Test
    @DisplayName("an attribute read comes back typed, not as a string")
    void readsAnAttribute()
    {
        Object version = brokerSession.requireManagement().attribute(ResourceNames.BROKER, "version");
        Object diskUsage = brokerSession.requireManagement().attribute(ResourceNames.BROKER, "diskStoreUsage");

        assertTrue(version instanceof String, String.valueOf(version));
        // The 0..1 ratio that once displayed as "0.10%" for an 85%-full disk.
        assertTrue(diskUsage instanceof Double, String.valueOf(diskUsage));
        assertTrue((Double) diskUsage <= 1.0d, String.valueOf(diskUsage));
    }

    @Test
    @DisplayName("an operation the broker refuses is reported with its reason and the usual cause")
    void reportsARejection()
    {
        BrokerException thrown = assertThrows(BrokerException.class, () -> brokerSession.requireManagement()
                .invoke(ResourceNames.QUEUE + "no-such-queue-here", "countMessages", ""));

        assertTrue(thrown.getMessage().contains("no-such-queue-here.countMessages"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'manage' permission"), thrown.getMessage());
    }

    @Test
    @DisplayName("a reply that never arrives gives up at the timeout rather than hanging the request")
    void reportsATimeout() throws Exception
    {
        // Addressed somewhere that is not the management address, so nothing is listening to
        // answer and the receive runs out. Only ever done against this throwaway container.
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url()))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            try (ManagementChannel channel = new ManagementChannel(session, "it-nobody-is-listening", 400))
            {
                long started = System.currentTimeMillis();
                BrokerException thrown = assertThrows(BrokerException.class,
                        () -> channel.invoke(ResourceNames.BROKER, "getQueueNames"));
                long waited = System.currentTimeMillis() - started;

                assertTrue(thrown.getMessage().contains("400ms"), thrown.getMessage());
                assertTrue(thrown.getMessage().contains("did not answer"), thrown.getMessage());
                assertTrue(waited >= 400, "gave up after " + waited + "ms, before the timeout");
            }
            finally
            {
                session.close();
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("the reply queue is a real queue on the broker, which is why the listing hides it")
    void itsReplyQueueIsVisibleToTheBroker()
    {
        String replyQueue = brokerSession.requireManagement().replyQueueName();
        Object listed = brokerSession.requireManagement().invoke(ResourceNames.BROKER, "listQueues",
                "{\"field\":\"\",\"operation\":\"\",\"value\":\"\"}", 1, 200);

        assertTrue(listed.toString().contains(replyQueue), "the broker does not list " + replyQueue);
        assertFalse(new QueueDirectory(brokerSession).queueNames().contains(replyQueue),
                "the queue listing exposes this tool's own plumbing");
        assertEquals(1, new QueueDirectory(brokerSession).overview().stream()
                .filter(queue -> queue.name().equals(ArtemisBrokerSupport.TEXT_QUEUE)).count());
    }

    @Test
    @DisplayName("a call on a connection that has gone says the connection has gone")
    void reportsALostConnection() throws Exception
    {
        // Its own session, closed underneath the channel: the same thing a broker restart does to
        // every session on it, without taking the container down for the other tests.
        BrokerSession doomed = ArtemisBrokerSupport.connect();
        ManagementChannel channel = doomed.requireManagement();
        doomed.close();

        ConnectionLostException thrown = assertThrows(ConnectionLostException.class,
                () -> channel.invoke(ResourceNames.BROKER, "listQueues",
                        "{\"field\":\"\",\"operation\":\"\"," + "\"value\":\"\"}", 1, 200));

        assertTrue(thrown.getMessage().contains("connection to the broker was lost"), thrown.getMessage());
        // Not a BrokerException: the controllers catch those to show an error beside the page, and
        // this one has to reach the handler that sends the user back to the connect form.
        assertFalse(BrokerException.class.isInstance(thrown),
                "a lost connection must not be catchable as a broker error");
    }
}
