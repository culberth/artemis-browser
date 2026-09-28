package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A message killed or expired where there is nowhere to send it is gone, and the queue's counter looks exactly as it
 * does when the message was kept. Built on a real broker: the address settings are changed through management as test
 * setup — something the app itself refuses to do — and the messages are killed and expired for real.
 */
class KilledAndExpiredIT
{

    /** Two delivery attempts, then killed, with no dead-letter address: dropped. */
    private static final String LOST = "it-lost";
    /** Expires after a second, with no expiry address: dropped. */
    private static final String STALE = "it-stale";
    /** Killed on the default settings, which dead-letter to DLQ: kept, and not a finding. */
    private static final String KEPT = "it-kept";

    private static BrokerSession brokerSession;
    private static QueueDirectory queues;

    @BeforeAll
    static void loseSomeMessages() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        queues = new QueueDirectory(brokerSession);
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url()))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            try
            {
                connection.start();
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                manage(session, "addAddressSettings", LOST, "{\"maxDeliveryAttempts\":2,\"deadLetterAddress\":\"\"}");
                manage(session, "addAddressSettings", STALE, "{\"expiryAddress\":\"\"}");

                send(session, LOST, 0);
                send(session, KEPT, 0);
                send(session, STALE, 1000);

                rollBackUntilGone(connection, LOST);
                rollBackUntilGone(connection, KEPT);

                Thread.sleep(1500);
                // Delivering an expired message expires it; nothing is received.
                assertNull(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
                        .createConsumer(session.createQueue(STALE)).receive(1500));
            }
            finally
            {
                connection.close();
            }
        }
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
    @DisplayName("the counters are read from the queue listing, where the messages left")
    void readsTheCounters()
    {
        assertEquals(1, queues.stats(LOST).messagesKilled());
        assertEquals(1, queues.stats(KEPT).messagesKilled());
        assertEquals(1, queues.stats(STALE).messagesExpired());
        assertEquals(0, queues.stats(LOST).messageCount(), "killed and dropped: gone from the queue");
    }

    @Test
    @DisplayName("diagnose reports the messages that had nowhere to go, and not the one that went to DLQ")
    void diagnosesMessagesWithNowhereToGo()
    {
        List<Finding> findings = new StuckDiagnosisService(queues, new AddressDirectory(brokerSession, queues),
                new BrokerInfoService(brokerSession),
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L),
                new DivertDirectory(brokerSession),
                new InFlightService(brokerSession, new BrokerInfoService(brokerSession), 5000),
                new RateService(brokerSession, new RateTracker(), new QueueDirectory(brokerSession))).diagnose(false);

        Finding lost = findings.stream().filter(f -> LOST.equals(f.queue()) && f.title().contains("delivery attempts"))
                .findFirst().orElseThrow(() -> new AssertionError("no finding for " + LOST + " in " + findings));
        assertTrue(lost.isStuck());
        assertTrue(lost.detail().contains("no dead-letter address"), lost.detail());

        Finding stale = findings.stream().filter(f -> STALE.equals(f.queue()) && f.title().contains("expired"))
                .findFirst().orElseThrow(() -> new AssertionError("no finding for " + STALE + " in " + findings));
        assertFalse(stale.isStuck());

        assertTrue(findings.stream().noneMatch(f -> KEPT.equals(f.queue()) && f.title().contains("delivery attempts")),
                "a message dead-lettered to DLQ was kept, not lost");
    }

    private static void send(Session session, String queue, long timeToLive) throws Exception
    {
        try (MessageProducer producer = session.createProducer(session.createQueue(queue)))
        {
            if (timeToLive > 0)
            {
                producer.setTimeToLive(timeToLive);
            }
            producer.send(session.createTextMessage(queue));
        }
    }

    /** Receive and roll back until the broker stops redelivering: the message has been killed. */
    private static void rollBackUntilGone(Connection connection, String queue) throws Exception
    {
        Session transacted = connection.createSession(true, Session.SESSION_TRANSACTED);
        MessageConsumer consumer = transacted.createConsumer(transacted.createQueue(queue));
        for (int attempt = 0; attempt < 20 && consumer.receive(2000) != null; attempt++)
        {
            transacted.rollback();
        }
        transacted.close();
    }

    /** Test setup through the management address directly; the app's own channel refuses anything but a read. */
    private static void manage(Session session, String operation, Object... params) throws Exception
    {
        TemporaryQueue reply = session.createTemporaryQueue();
        try (MessageProducer producer = session.createProducer(session.createQueue("activemq.management"));
                MessageConsumer consumer = session.createConsumer(reply))
        {
            Message request = session.createMessage();
            JMSManagementHelper.putOperationInvocation(request, ResourceNames.BROKER, operation, params);
            request.setJMSReplyTo(reply);
            producer.send(request);
            Message answer = consumer.receive(10000);
            if (answer == null || !JMSManagementHelper.hasOperationSucceeded(answer))
            {
                throw new IllegalStateException(operation + " failed: "
                        + (answer == null ? "no answer" : JMSManagementHelper.getResult(answer)));
            }
        }
        finally
        {
            reply.delete();
        }
    }
}
