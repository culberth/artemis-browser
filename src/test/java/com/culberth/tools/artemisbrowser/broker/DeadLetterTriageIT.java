package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.broker.DeadLetterTriage.Origin;
import com.culberth.tools.artemisbrowser.broker.DeadLetterTriage.ValueGroup;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import tools.jackson.databind.ObjectMapper;

/**
 * Dead-letter and expiry triage against messages the broker really dead-lettered, expired and moved, on every supported
 * version.
 *
 * <p>
 * Every way a message reaches a dead-letter queue that this tool can tell apart, and the ones it cannot: killed after
 * its delivery attempts on an anycast queue and on a multicast subscription, sent to the dead-letter address by an
 * operator (which looks exactly like a kill), sent there directly (no origin at all), and an AMQP message, whose origin
 * the browse reply names differently. Plus core and AMQP messages the broker expired, and one moved by hand to an
 * ordinary queue. Address settings and the moves are test setup through the management address — things the app itself
 * refuses to do. Triage reads with {@code browse} alone, and every counter is the same afterwards.
 */
class DeadLetterTriageIT
{

    private static final String DEAD = "it-dlt.dead";
    private static final String EXPIRED = "it-dlt.expired";
    private static final String PLAIN = "it-dlt.plain";
    private static final String ORDERS = "it-dlt.orders";
    private static final String EVENTS = "it-dlt.events";
    private static final String CLIENT = "it-dlt-client";
    /** A JMS durable subscription's queue is {@code clientId.subscriptionName}. */
    private static final String SUBSCRIPTION = CLIENT + ".audit";
    private static final String MANUAL = "it-dlt.manual";
    private static final String AMQP = "it-dlt.amqp";
    private static final String STALE = "it-dlt.stale";
    private static final String AMQP_STALE = "it-dlt.amqp-stale";

    private static BrokerSession brokerSession;
    private static DeadLetterTriageService triage;
    private static QueueDirectory queues;

    @BeforeAll
    static void deadLetterSomeMessages() throws Exception
    {
        String url = ArtemisBrokerSupport.start();
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            connection.setClientID(CLIENT);
            try
            {
                connection.start();
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                manage(session, ResourceNames.BROKER, "addAddressSettings", "it-dlt.#", "{\"maxDeliveryAttempts\":2,"
                        + "\"deadLetterAddress\":\"" + DEAD + "\",\"expiryAddress\":\"" + EXPIRED + "\"}");
                // A dead-letter or expiry address with no queue drops what is sent to it.
                for (String queue : List.of(DEAD, EXPIRED, PLAIN))
                {
                    session.createConsumer(session.createQueue(queue)).close();
                }

                Queue orders = session.createQueue(ORDERS);
                send(session, orders, "E1", 0);
                send(session, orders, "E1", 0);
                send(session, orders, "E2", 0);
                rollBackUntilGone(connection, orders);

                Topic events = session.createTopic(EVENTS);
                session.createDurableSubscriber(events, "audit").close();
                send(session, events, null, 0);
                rollBackUntilGone(connection, session.createQueue(EVENTS + "::" + SUBSCRIPTION));

                send(session, session.createQueue(MANUAL), null, 0);
                send(session, session.createQueue(MANUAL), null, 0);
                manage(session, ResourceNames.QUEUE + MANUAL, "sendMessageToDeadLetterAddress",
                        firstCoreId(session, MANUAL));
                manage(session, ResourceNames.QUEUE + MANUAL, "moveMessages", "", PLAIN);

                send(session, session.createQueue(DEAD), null, 0);

                Container.ExecResult amqp = ArtemisBrokerSupport.exec("/var/lib/artemis-instance/bin/artemis",
                        "producer", "--protocol", "amqp", "--user", ArtemisBrokerSupport.USER, "--password",
                        ArtemisBrokerSupport.PASSWORD, "--destination", "queue://" + AMQP, "--message-count", "1",
                        "--url", "amqp://localhost:61616");
                assertEquals(0, amqp.getExitCode(), amqp.getStderr());
                rollBackUntilGone(connection, session.createQueue(AMQP));

                send(session, session.createQueue(STALE), null, 500);
                Container.ExecResult amqpStale = ArtemisBrokerSupport.exec("/var/lib/artemis-instance/bin/artemis",
                        "producer", "--protocol", "amqp", "--user", ArtemisBrokerSupport.USER, "--password",
                        ArtemisBrokerSupport.PASSWORD, "--destination", "queue://" + AMQP_STALE, "--message-count", "1",
                        "--msgttl", "500", "--url", "amqp://localhost:61616");
                assertEquals(0, amqpStale.getExitCode(), amqpStale.getStderr());
                Thread.sleep(1000);
                // Delivering an expired message expires it; nothing is received.
                for (String queue : List.of(STALE, AMQP_STALE))
                {
                    try (MessageConsumer consumer = session.createConsumer(session.createQueue(queue)))
                    {
                        assertEquals(null, consumer.receive(1500), queue + " was delivered instead of expired");
                    }
                }
            }
            finally
            {
                connection.close();
            }
        }
        brokerSession = ArtemisBrokerSupport.connect();
        queues = new QueueDirectory(brokerSession);
        triage = new DeadLetterTriageService(brokerSession, queues,
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L),
                new AddressDirectory(brokerSession, queues), 500, 2000, 50, 20);
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
    @DisplayName("a dead-letter queue is grouped by origin: anycast, multicast subscription, operator, AMQP, unknown")
    void groupsTheDeadLetterQueue()
    {
        DeadLetterTriage dead = triage.triage(DEAD, null, 0, null);

        assertEquals(7, dead.sampled(), origins(dead));
        assertTrue(dead.wholeQueue());
        assertEquals(1, dead.noOrigin(), "the message sent here directly records no origin");
        assertEquals(0, dead.expired());

        Origin orders = origin(dead, ORDERS);
        assertEquals(ORDERS, orders.queue());
        assertEquals(3, orders.count());
        assertEquals(List.of("anycast"), orders.routingTypes());
        assertTrue(orders.deadLettersHere());
        assertTrue(orders.queueExists());
        assertEquals(3, orders.examples().size());

        Origin events = origin(dead, EVENTS);
        assertEquals(SUBSCRIPTION, events.queue(), "the subscription queue, not the address");
        assertEquals(List.of("multicast"), events.routingTypes());
        assertTrue(events.queueExists());

        // Sent there by an operator, and indistinguishable from a kill: same origin, same settings.
        Origin manual = origin(dead, MANUAL);
        assertEquals(1, manual.count());
        assertTrue(manual.deadLettersHere());

        Origin amqp = origin(dead, AMQP);
        assertEquals(AMQP, amqp.queue());
        assertEquals(List.of("anycast"), amqp.routingTypes());
        assertEquals(List.of("AMQP"), amqp.protocols());

        Origin unknown = dead.origins().stream().filter(o -> !o.known()).findFirst().orElseThrow();
        assertEquals(1, unknown.count());
    }

    @Test
    @DisplayName("grouping by a property counts values the sender set, and the messages without it as not set")
    void groupsByAProperty()
    {
        DeadLetterTriage dead = triage.triage(DEAD, null, 0, "code");

        Map<String, Integer> counts = dead.groupBy().values().stream()
                .collect(Collectors.toMap(ValueGroup::value, ValueGroup::count));
        assertEquals(Map.of("E1", 2, "E2", 1), counts);
        assertEquals(4, dead.groupBy().notSet());
        assertEquals(List.of(ORDERS + " / " + ORDERS), dead.groupBy().values().getFirst().origins());
    }

    @Test
    @DisplayName("expired messages carry the time the broker expired them, core and AMQP alike")
    void recognisesExpiry()
    {
        DeadLetterTriage expired = triage.triage(EXPIRED, null, 0, null);

        assertEquals(2, expired.sampled(), origins(expired));
        assertEquals(2, expired.expired(), "both messages record _AMQ_ACTUAL_EXPIRY: " + origins(expired));
        Origin stale = origin(expired, STALE);
        assertTrue(stale.expiresHere());
        assertFalse(stale.deadLettersHere());
        assertNotNull(stale.firstExpired());
        assertEquals(1, origin(expired, AMQP_STALE).expired());
    }

    @Test
    @DisplayName("a message moved by hand to an ordinary queue has an origin whose settings send nothing there")
    void recognisesAManualMove()
    {
        DeadLetterTriage plain = triage.triage(PLAIN, null, 0, null);

        Origin manual = origin(plain, MANUAL);
        assertEquals(1, manual.count());
        assertTrue(manual.settingsRead());
        assertFalse(manual.deadLettersHere());
        assertFalse(manual.expiresHere());
    }

    @Test
    @DisplayName("a smaller sample is the head of the queue and says it is not the whole of it")
    void samplesTheHead()
    {
        DeadLetterTriage head = triage.triage(DEAD, null, 2, null);

        assertEquals(2, head.sampled());
        assertFalse(head.reachedEnd());
        assertFalse(head.wholeQueue());
        assertEquals(ORDERS, head.origins().getFirst().address(), "the oldest messages come first");
    }

    @Test
    @DisplayName("the broker filters on the origin property, and rejects a filter it cannot parse")
    void filtersOnOrigin()
    {
        DeadLetterTriage filtered = triage.triage(DEAD, "_AMQ_ORIG_ADDRESS = '" + ORDERS + "'", 0, null);

        assertEquals(3, filtered.sampled());
        assertEquals(1, filtered.origins().size());
        assertThrows(InvalidFilterException.class, () -> triage.triage(DEAD, "==", 0, null));
    }

    @Test
    @DisplayName("triage consumes nothing: every counter is the same after reading each queue three times")
    void consumesNothing()
    {
        Map<String, String> before = counters();
        for (int round = 0; round < 3; round++)
        {
            for (String queue : List.of(DEAD, EXPIRED, PLAIN))
            {
                triage.triage(queue, null, 0, "code");
            }
        }
        assertEquals(before, counters());
    }

    private static Map<String, String> counters()
    {
        return queues.overview().stream().filter(q -> q.name().startsWith("it-dlt"))
                .collect(Collectors.toMap(QueueOverview::name, q -> q.messageCount() + "/" + q.deliveringCount() + "/"
                        + q.messagesAcked() + "/" + q.messagesAdded() + "/" + q.messagesKilled()));
    }

    private static Origin origin(DeadLetterTriage triage, String address)
    {
        return triage.origins().stream().filter(o -> address.equals(o.address())).findFirst()
                .orElseThrow(() -> new AssertionError("no origin " + address + " in " + origins(triage)));
    }

    private static String origins(DeadLetterTriage triage)
    {
        return triage.origins().stream().map(o -> o.label() + "×" + o.count() + " expired " + o.expired())
                .collect(Collectors.joining(", ")) + "; properties " + triage.properties();
    }

    private static void send(Session session, Destination destination, String code, long timeToLive) throws Exception
    {
        try (MessageProducer producer = session.createProducer(destination))
        {
            if (timeToLive > 0)
            {
                producer.setTimeToLive(timeToLive);
            }
            TextMessage message = session.createTextMessage("triage");
            if (code != null)
            {
                message.setStringProperty("code", code);
            }
            producer.send(message);
        }
    }

    /** Receive and roll back until the broker stops redelivering: each message has been killed. */
    private static void rollBackUntilGone(Connection connection, Queue queue) throws Exception
    {
        Session transacted = connection.createSession(true, Session.SESSION_TRANSACTED);
        MessageConsumer consumer = transacted.createConsumer(queue);
        for (int attempt = 0; attempt < 20 && consumer.receive(2000) != null; attempt++)
        {
            transacted.rollback();
        }
        transacted.close();
    }

    /** The core id of a queue's first message, which is what the management message operations take. */
    private static long firstCoreId(Session session, String queue) throws Exception
    {
        String json = String.valueOf(manage(session, ResourceNames.QUEUE + queue, "listMessagesAsJSON", ""));
        return new ObjectMapper().readTree(json).get(0).get("messageID").asLong();
    }

    /** Test setup through the management address directly; the app's own channel refuses anything but a read. */
    private static Object manage(Session session, String resource, String operation, Object... params) throws Exception
    {
        TemporaryQueue reply = session.createTemporaryQueue();
        try (MessageProducer producer = session.createProducer(session.createQueue("activemq.management"));
                MessageConsumer consumer = session.createConsumer(reply))
        {
            Message request = session.createMessage();
            JMSManagementHelper.putOperationInvocation(request, resource, operation, params);
            request.setJMSReplyTo(reply);
            producer.send(request);
            Message answer = consumer.receive(10000);
            if (answer == null || !JMSManagementHelper.hasOperationSucceeded(answer))
            {
                throw new IllegalStateException(operation + " failed: "
                        + (answer == null ? "no answer" : JMSManagementHelper.getResult(answer)));
            }
            return JMSManagementHelper.getResult(answer);
        }
        finally
        {
            reply.delete();
        }
    }
}
