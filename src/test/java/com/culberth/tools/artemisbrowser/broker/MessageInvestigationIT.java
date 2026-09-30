package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.Hit;
import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.Outcome;
import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.QueueCoverage;
import com.culberth.tools.artemisbrowser.broker.MessageInvestigation.State;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One message looked up by ID in every state a real broker can hold it in, and what the lookup does — which must be
 * nothing.
 *
 * <p>
 * Its own broker, like {@link TransactionsIT}: it leaves a message scheduled an hour ahead, a consumer holding messages
 * unacknowledged and a prepared XA branch, all of which the shared broker's diagnose tests would rightly report.
 */
class MessageInvestigationIT
{

    private static final String WAITING = "it-inv.waiting";
    private static final String SCHEDULED = "it-inv.scheduled";
    private static final String HELD = "it-inv.held";
    private static final String TOPIC = "it-inv.topic";
    private static final String XA_SOURCE = "it-inv.xa.src";
    private static final String XA_TARGET = "it-inv.xa.dst";

    private static GenericContainer<?> container;
    private static BrokerSession brokerSession;
    private static QueueDirectory queues;
    private static QueueBrowseService browse;
    private static InFlightService inFlight;
    private static TransactionService transactions;

    private static ActiveMQConnectionFactory holderFactory;
    private static Connection holder;
    private static String waitingId;
    private static String scheduledId;
    private static String heldId;
    private static String fannedOutId;

    @BeforeAll
    static void start() throws Exception
    {
        container = new GenericContainer<>(DockerImageName.parse(ArtemisBrokerSupport.IMAGE))
                .withEnv("ARTEMIS_USER", ArtemisBrokerSupport.USER)
                .withEnv("ARTEMIS_PASSWORD", ArtemisBrokerSupport.PASSWORD).withExposedPorts(61616)
                .waitingFor(Wait.forLogMessage(".*Server is now active.*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(4));
        container.start();

        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url()))
        {
            // Two durable subscriptions on one topic, made before anything is published to it.
            try (Connection subscriber = factory.createConnection(ArtemisBrokerSupport.USER,
                    ArtemisBrokerSupport.PASSWORD))
            {
                subscriber.setClientID("it-inv");
                Session session = subscriber.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Topic topic = session.createTopic(TOPIC);
                session.createDurableSubscriber(topic, "a").close();
                session.createDurableSubscriber(topic, "b").close();
            }

            try (Connection plain = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD))
            {
                Session session = plain.createSession(false, Session.AUTO_ACKNOWLEDGE);
                waitingId = send(session.createProducer(session.createQueue(WAITING)), session, "waiting", 0);

                MessageProducer delayed = session.createProducer(session.createQueue(SCHEDULED));
                scheduledId = send(delayed, session, "an hour from now", 3_600_000);

                fannedOutId = send(session.createProducer(session.createTopic(TOPIC)), session, "to both", 0);

                MessageProducer toHeld = session.createProducer(session.createQueue(HELD));
                for (int i = 0; i < 3; i++)
                {
                    send(toHeld, session, "held-" + i, 0);
                }
            }
        }

        // Received and never acknowledged, and the rest buffered: all three delivering. Its own
        // factory, kept open: closing a factory closes the connections it made.
        holderFactory = new ActiveMQConnectionFactory(url());
        holder = holderFactory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
        holder.start();
        Session session = holder.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        Message received = session.createConsumer(session.createQueue(HELD)).receive(5000);
        assertNotNull(received);
        heldId = received.getJMSMessageID();

        XaFixtures.prepare(url(), "inv", XA_SOURCE, XA_TARGET, true);

        brokerSession = new BrokerSession(10000, 10000);
        brokerSession.connect(new BrokerCredentials(container.getHost(), container.getMappedPort(61616),
                ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD));
        queues = new QueueDirectory(brokerSession);
        browse = new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L);
        inFlight = new InFlightService(brokerSession, new BrokerInfoService(brokerSession), 5000);
        transactions = new TransactionService(brokerSession, 100);
        awaitDelivering(HELD, 3);
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (holder != null)
        {
            holder.close();
            holderFactory.close();
        }
        if (brokerSession != null)
        {
            brokerSession.close();
        }
        if (container != null)
        {
            container.stop();
        }
    }

    @Test
    @DisplayName("a waiting message is seen waiting, once, on its queue")
    void findsAWaitingMessage()
    {
        MessageInvestigation result = service().investigate(waitingId, false);

        assertEquals(1, result.hits().size(), result.hits().toString());
        Hit hit = result.hits().get(0);
        assertEquals(State.WAITING, hit.state());
        assertEquals(WAITING, hit.queueName());
        assertEquals(waitingId, hit.waiting().messageId());
        assertTrue(result.complete(), result.incomplete().toString());
    }

    @Test
    @DisplayName("a scheduled message, which browse cannot see, is seen scheduled")
    void findsAScheduledMessage()
    {
        MessageInvestigation result = service().investigate(scheduledId, false);

        assertEquals(1, result.hits().size(), result.hits().toString());
        assertEquals(State.SCHEDULED, result.hits().get(0).state());
        assertEquals(SCHEDULED, result.hits().get(0).queueName());
        assertFalse(result.hits().get(0).scheduled().overdue());
        assertEquals(Outcome.CHECKED, coverage(result, SCHEDULED).scheduled().outcome());
    }

    @Test
    @DisplayName("a message held unacknowledged is seen in flight, with the consumer holding it")
    void findsAnInFlightMessage()
    {
        MessageInvestigation result = service().investigate(heldId, false);

        assertEquals(1, result.hits().size(), result.hits().toString());
        Hit hit = result.hits().get(0);
        assertEquals(State.IN_FLIGHT, hit.state());
        assertEquals(HELD, hit.queueName());
        assertTrue(hit.holder().identified(), hit.holder().consumerName());
        assertTrue(hit.holder().client() != null, "the holder was not matched to a client");
    }

    @Test
    @DisplayName("a message on a multicast address is seen once per subscription")
    void findsEachSubscriptionCopy()
    {
        MessageInvestigation result = service().investigate(fannedOutId, false);

        assertEquals(List.of("it-inv.a", "it-inv.b"), result.hits().stream().map(Hit::queueName).sorted().toList());
        assertTrue(result.hits().stream().allMatch(hit -> TOPIC.equals(hit.address())));
    }

    @Test
    @DisplayName("messages in a prepared branch are seen there: the received one by its address, the sent one on none")
    void findsPreparedMessages()
    {
        PreparedTransaction branch = transactions.collect().prepared().value().get(0);
        TransactionMessage received = branch.messages().stream().filter(TransactionMessage::receive).findFirst()
                .orElseThrow();
        TransactionMessage sent = branch.messages().stream().filter(TransactionMessage::send).findFirst().orElseThrow();

        MessageInvestigation byReceived = service().investigate(received.userId(), false);
        MessageInvestigation bySent = service().investigate(sent.userId(), false);

        assertEquals(1, byReceived.hits().size(), byReceived.hits().toString());
        assertEquals(State.PREPARED_RECEIVE, byReceived.hits().get(0).state());
        assertEquals(XA_SOURCE, byReceived.hits().get(0).address());
        // Its queue counts it as delivering, and the delivering list, read in full, does not have it.
        assertEquals(Outcome.CHECKED, coverage(byReceived, XA_SOURCE).inFlight().outcome());

        assertEquals(1, bySent.hits().size(), bySent.hits().toString());
        assertEquals(State.PREPARED_SEND, bySent.hits().get(0).state());
        assertEquals(XA_TARGET, bySent.hits().get(0).address());
        assertEquals(Outcome.CHECKED, bySent.prepared().outcome());
    }

    @Test
    @DisplayName("an ID nobody sent is not seen, with every queue covered")
    void seesNothingForAnUnknownId()
    {
        MessageInvestigation result = service().investigate("ID:00000000-0000-0000-0000-000000000000", false);

        assertFalse(result.found());
        assertTrue(result.complete(), result.incomplete().toString());
        assertTrue(result.coverage().size() >= 6, result.coverage().toString());
    }

    @Test
    @DisplayName("a message that moves from scheduled to waiting between two lookups is seen in each state in turn")
    void followsAMessageThatMoves() throws Exception
    {
        String queue = "it-inv.moving";
        String id;
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url());
                Connection plain = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD))
        {
            Session session = plain.createSession(false, Session.AUTO_ACKNOWLEDGE);
            id = send(session.createProducer(session.createQueue(queue)), session, "soon", 3000);
        }

        assertEquals(State.SCHEDULED, service().investigate(id, false).hits().get(0).state());

        MessageInvestigation later = null;
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline)
        {
            later = service().investigate(id, false);
            if (!later.hits().isEmpty() && later.hits().get(0).state() == State.WAITING)
            {
                break;
            }
            Thread.sleep(250);
        }
        assertNotNull(later);
        assertEquals(1, later.hits().size(), later.hits().toString());
        assertEquals(State.WAITING, later.hits().get(0).state());
    }

    @Test
    @DisplayName("queues over the in-flight and scheduled limits are skipped before the call, and said to be")
    void skipsOverItsBudgets()
    {
        MessageInvestigation result = new MessageInvestigationService(queues, browse,
                new InFlightService(brokerSession, new BrokerInfoService(brokerSession), 2), transactions, 2000, 100,
                20000, 0, 20000).investigate(heldId, false);

        assertFalse(result.found(), "the held message was over the per-queue limit and should not have been read");
        assertEquals(Outcome.SKIPPED, coverage(result, HELD).inFlight().outcome());
        assertEquals(Outcome.SKIPPED, coverage(result, SCHEDULED).scheduled().outcome());
        assertFalse(result.complete());
    }

    @Test
    @DisplayName("looking a message up in every state moves no counter on any queue")
    void investigatingChangesNothing()
    {
        Map<String, String> before = counters();

        for (String id : List.of(waitingId, scheduledId, heldId, fannedOutId, "ID:nobody"))
        {
            service().investigate(id, true);
        }

        assertEquals(before, counters(), "a lookup moved a counter");
    }

    private static MessageInvestigationService service()
    {
        return new MessageInvestigationService(queues, browse, inFlight, transactions, 2000, 100, 20000, 5000, 20000);
    }

    private static QueueCoverage coverage(MessageInvestigation result, String queue)
    {
        return result.coverage().stream().filter(row -> row.queueName().equals(queue)).findFirst().orElseThrow();
    }

    private static String send(MessageProducer producer, Session session, String body, long delayMillis)
            throws Exception
    {
        producer.setDeliveryDelay(delayMillis);
        TextMessage message = session.createTextMessage(body);
        producer.send(message);
        return message.getJMSMessageID();
    }

    private static Map<String, String> counters()
    {
        Map<String, String> counters = new LinkedHashMap<>();
        for (QueueOverview queue : queues.overview())
        {
            if (queue.name().startsWith("it-inv"))
            {
                counters.put(queue.name(),
                        queue.messageCount() + "/" + queue.deliveringCount() + "/" + queue.scheduledCount() + "/"
                                + queue.messagesAdded() + "/" + queue.messagesAcked() + "/" + queue.messagesExpired()
                                + "/" + queue.messagesKilled());
            }
        }
        return counters;
    }

    private static void awaitDelivering(String queue, long expected) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline)
        {
            QueueStats stats = queues.stats(queue);
            if (stats != null && stats.deliveringCount() == expected)
            {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(queue + " never reached " + expected + " delivering");
    }

    private static String url()
    {
        return "tcp://" + container.getHost() + ":" + container.getMappedPort(61616)
                + "?useTopologyForLoadBalancing=false";
    }
}
