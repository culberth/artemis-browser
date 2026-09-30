package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One queue per setting, on a real broker, because none of what these settings do to the counters is what the names
 * suggest: a last-value queue reports {@code lastValue} false, replaced and evicted messages touch no counter,
 * consuming from a non-destructive queue acknowledges nothing, and a purge counts as killed. Queues are created through
 * management as test setup — something the app itself refuses to do.
 */
class QueueBehaviorIT
{

    private static final String LVQ = "it-qb-lvq";
    private static final String RING = "it-qb-ring";
    private static final String KEEP = "it-qb-keep";
    private static final String PURGE = "it-qb-purge";
    private static final String EXCLUSIVE = "it-qb-exclusive";
    private static final String GROUPED = "it-qb-grouped";
    private static final String GATED = "it-qb-gated";

    private static BrokerSession brokerSession;
    private static QueueDirectory queues;
    private static Connection holders;
    private static ActiveMQConnectionFactory factory;

    @BeforeAll
    static void configureQueues() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        queues = new QueueDirectory(brokerSession);
        factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url());
        Connection setup = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
        try
        {
            setup.start();
            Session session = setup.createSession(false, Session.AUTO_ACKNOWLEDGE);
            create(session, LVQ, "\"last-value-key\":\"k\"");
            create(session, RING, "\"ring-size\":3");
            create(session, KEEP, "\"non-destructive\":true");
            create(session, PURGE, "\"purge-on-no-consumers\":true");
            create(session, EXCLUSIVE, "\"exclusive\":true");
            create(session, GROUPED, "");
            create(session, GATED, "\"consumers-before-dispatch\":2");
            // No dead-letter address: a killed message on PURGE then has nowhere to go, which is
            // what makes diagnose look at it.
            manage(session, "addAddressSettings", PURGE, "{\"deadLetterAddress\":\"\"}");

            send(session, LVQ, 5, (message, i) -> property(message, "k", "same"));
            send(session, RING, 10, (message, i) ->
            {
            });
            send(session, KEEP, 3, (message, i) ->
            {
            });

            // Non-destructive: take all three, acknowledged.
            MessageConsumer taker = session.createConsumer(session.createQueue(KEEP));
            for (int i = 0; i < 3; i++)
            {
                assertNotNull(taker.receive(5000), "non-destructive still delivers");
            }
            taker.close();

            // Purge: three in flight to a consumer that never acknowledges, then it leaves.
            Session unacked = setup.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            MessageConsumer leaving = unacked.createConsumer(unacked.createQueue(PURGE));
            send(session, PURGE, 3, (message, i) ->
            {
            });
            for (int i = 0; i < 3; i++)
            {
                leaving.receive(5000);
            }
            leaving.close();
            unacked.close();
        }
        finally
        {
            setup.close();
        }

        // Consumers held open for the life of the class: two on the exclusive queue, two on the
        // grouped one, one on the queue that waits for two. They never receive, so what the broker
        // dispatches sits in their buffers as in flight.
        holders = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
        holders.start();
        Session held = holders.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        for (String queue : List.of(EXCLUSIVE, EXCLUSIVE, GROUPED, GROUPED, GATED))
        {
            held.createConsumer(held.createQueue(queue));
        }
        Thread.sleep(500);
        Session session = holders.createSession(false, Session.AUTO_ACKNOWLEDGE);
        send(session, EXCLUSIVE, 20, (message, i) ->
        {
        });
        send(session, GROUPED, 20, (message, i) -> property(message, "JMSXGroupID", i % 2 == 0 ? "g1" : "g2"));
        send(session, GATED, 3, (message, i) ->
        {
        });
        Thread.sleep(1000);
    }

    @AfterAll
    static void disconnect() throws Exception
    {
        if (holders != null)
        {
            holders.close();
        }
        if (factory != null)
        {
            factory.close();
        }
        if (brokerSession != null)
        {
            brokerSession.close();
        }
    }

    @Test
    @DisplayName("the listing's settings are parsed as recorded, and replaced or evicted messages touch no counter")
    void readsTheConfiguration()
    {
        QueueOverview lvq = find(LVQ);
        assertEquals("k", lvq.behavior().lastValueOn(), "the key, although the broker reports lastValue false");
        assertEquals(1, lvq.messageCount());
        assertEquals(5, lvq.messagesAdded());
        assertEquals(0, lvq.messagesKilled() + lvq.messagesAcked());

        QueueOverview ring = find(RING);
        assertEquals(3L, ring.behavior().ring());
        assertEquals(3, ring.messageCount());
        assertEquals(0, ring.messagesKilled() + ring.messagesAcked());

        assertTrue(find(EXCLUSIVE).behavior().singleConsumer());
        assertTrue(find(PURGE).behavior().purges());
        assertTrue(find(GATED).behavior().dispatchGated(1));
        assertEquals(Availability.UNSUPPORTED, find(KEEP).behavior().nonDestructive().availability());
    }

    @Test
    @DisplayName("what the settings do: non-destructive keeps and acknowledges nothing, a purge counts as killed")
    void observesTheSemantics()
    {
        QueueOverview keep = find(KEEP);
        assertEquals(3, keep.messageCount(), "consumed and still there");
        assertEquals(0, keep.messagesAcked());

        QueueOverview purge = find(PURGE);
        assertEquals(0, purge.messageCount());
        assertEquals(3, purge.messagesKilled());

        QueueOverview gated = find(GATED);
        assertEquals(1, gated.consumerCount());
        assertEquals(0, gated.deliveringCount(), "nothing dispatched with one of the two consumers it waits for");

        assertEquals(2L, queues.groupCount(GROUPED).value());
    }

    @Test
    @DisplayName("diagnose: no hoarding on the exclusive queue, the gate explained, the purge not called a failed delivery")
    void diagnosesWithTheConfiguration()
    {
        List<Finding> findings = diagnose();

        assertTrue(findings.stream().noneMatch(f -> EXCLUSIVE.equals(f.queue()) && f.title().contains("holds")),
                findings.toString());
        Finding gate = about(findings, GATED);
        assertTrue(gate.title().contains("waiting for 2 consumers"), gate.title());
        assertTrue(gate.hasExplanation());

        Finding purge = about(findings, PURGE);
        assertFalse(purge.title().contains("delivery attempts"), purge.title());
        assertTrue(purge.explanation().contains("purge"), purge.explanation());
    }

    @Test
    @DisplayName("browsing a last-value, ring or non-destructive queue replaces, evicts and acknowledges nothing")
    void readingChangesNothing()
    {
        QueueBrowseService browse = new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L);
        for (String queue : List.of(LVQ, RING, KEEP))
        {
            QueueOverview before = find(queue);
            browse.page(queue, null, 1, 50);
            QueueOverview after = find(queue);
            assertEquals(
                    List.of(before.messageCount(), before.messagesAdded(), before.messagesAcked(),
                            before.messagesKilled()),
                    List.of(after.messageCount(), after.messagesAdded(), after.messagesAcked(), after.messagesKilled()),
                    queue);
        }
    }

    private static List<Finding> diagnose()
    {
        return new StuckDiagnosisService(queues, new AddressDirectory(brokerSession, queues),
                new BrokerInfoService(brokerSession),
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L),
                new DivertDirectory(brokerSession),
                new InFlightService(brokerSession, new BrokerInfoService(brokerSession), 5000),
                new RateService(brokerSession, new RateTracker(), new QueueDirectory(brokerSession)),
                new ConnectivityService(brokerSession)).diagnose(false);
    }

    private static Finding about(List<Finding> findings, String queue)
    {
        return findings.stream().filter(f -> queue.equals(f.queue())).findFirst()
                .orElseThrow(() -> new AssertionError("no finding for " + queue + " in " + findings));
    }

    private static QueueOverview find(String name)
    {
        return queues.overview().stream().filter(queue -> queue.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no queue " + name));
    }

    private static void property(Message message, String key, String value)
    {
        try
        {
            message.setStringProperty(key, value);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static void send(Session session, String queue, int count, BiConsumer<TextMessage, Integer> prepare)
            throws Exception
    {
        try (MessageProducer producer = session.createProducer(session.createQueue(queue)))
        {
            for (int i = 0; i < count; i++)
            {
                TextMessage message = session.createTextMessage(queue + "-" + i);
                prepare.accept(message, i);
                producer.send(message);
            }
        }
    }

    private static void create(Session session, String name, String settings) throws Exception
    {
        List<String> fields = new ArrayList<>(
                List.of("\"name\":\"" + name + "\"", "\"address\":\"" + name + "\"", "\"routing-type\":\"ANYCAST\""));
        if (!settings.isEmpty())
        {
            fields.add(settings);
        }
        manage(session, "createQueue", "{" + String.join(",", fields) + "}", true);
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
