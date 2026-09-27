package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.broker.Subscription.Kind;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.Topic;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One address read end to end against a real broker: every kind of subscription, a live consumer with a client id, and
 * a producer attached and sending.
 *
 * <p>
 * The unit tests parse replies captured from a broker; this checks that the calls producing those replies are the ones
 * the code makes — the address filter on {@code listQueues} really narrows to one address, {@code listConsumers} really
 * carries the client id — which a captured reply cannot show.
 */
class AddressDetailIT
{

    private static BrokerSession brokerSession;
    private static AddressDetailService addresses;
    private static ActiveMQConnectionFactory factory;
    private static Connection live;

    @BeforeAll
    static void connect() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        QueueDirectory queues = new QueueDirectory(brokerSession);
        addresses = new AddressDetailService(new AddressDirectory(brokerSession, queues), queues,
                new BrokerInfoService(brokerSession),
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L),
                new DivertDirectory(brokerSession));

        // A non-durable subscription exists only while its consumer does, so it is held open for the
        // class. Client acknowledge and never acking: attached, and consuming nothing.
        factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url());
        live = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
        live.setClientID("it-live");
        live.start();
        Session session = live.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        Topic feed = session.createTopic(ArtemisBrokerSupport.FEED_ADDRESS);
        session.createConsumer(feed, "region = 'us'");
        MessageProducer producer = session.createProducer(feed);
        producer.send(session.createTextMessage("from a producer that stays attached"));
    }

    @AfterAll
    static void disconnect() throws Exception
    {
        if (live != null)
        {
            live.close();
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
    @DisplayName("every subscription on the address is listed, and nothing from any other address")
    void listsTheAddressesSubscriptions()
    {
        AddressDetail detail = addresses.detail(ArtemisBrokerSupport.FEED_ADDRESS);

        assertNotNull(detail);
        assertEquals(4, detail.subscriptions().size(), detail.subscriptions().toString());
        assertTrue(detail.subscriptions().stream()
                .allMatch(subscription -> ArtemisBrokerSupport.FEED_ADDRESS.equals(subscription.address())));
    }

    @Test
    @DisplayName("each kind is recognised, and a JMS selector comes back in core syntax")
    void describesEachSubscription()
    {
        Map<String, Subscription> byName = byName(addresses.detail(ArtemisBrokerSupport.FEED_ADDRESS));

        Subscription filtered = byName.get(ArtemisBrokerSupport.FEED_FILTERED);
        assertEquals(Kind.DURABLE, filtered.kind());
        assertEquals("it-client", filtered.clientIdHint());
        assertEquals("it-filtered", filtered.subscriptionHint());
        assertTrue(filtered.filter().contains("AMQPriority > 3"), filtered.filter());
        assertEquals(1, filtered.messageCount(), "only eu at priority 4 passes the filter");

        Subscription abandoned = byName.get(ArtemisBrokerSupport.FEED_ABANDONED);
        assertFalse(abandoned.filtered());
        assertTrue(abandoned.stalled());
        assertEquals(ArtemisBrokerSupport.FEED_MESSAGES + 1, abandoned.messageCount());

        Subscription shared = byName.get(ArtemisBrokerSupport.FEED_SHARED);
        assertEquals(Kind.DURABLE, shared.kind());
        assertNull(shared.clientIdHint());

        Subscription nonDurable = detail().subscriptions().stream()
                .filter(subscription -> subscription.kind() == Kind.NON_DURABLE).findFirst().orElseThrow();
        assertEquals("region = 'us'", nonDurable.filter());
    }

    @Test
    @DisplayName("the live consumer is shown with its client id, and the attached producer is found by address")
    void findsWhoIsAttached()
    {
        AddressDetail detail = detail();

        assertEquals(1, detail.consumers().size(), detail.consumers().toString());
        SubscriberConsumer consumer = detail.consumers().get(0);
        assertEquals("it-live", consumer.clientId());
        assertFalse(consumer.remoteAddress().isEmpty());

        assertEquals(1, detail.producers().size(), detail.producers().toString());
        assertTrue(detail.producers().get(0).messagesSent() >= 1);
    }

    @Test
    @DisplayName("lag is the age of the oldest undelivered message, and one burst marks no subscription furthest behind")
    void measuresLagByAge()
    {
        AddressDetail detail = detail();
        Subscription abandoned = byName(detail).get(ArtemisBrokerSupport.FEED_ABANDONED);

        assertNotNull(detail.oldestUndelivered(abandoned));
        assertTrue(detail.oldestUndelivered(abandoned) >= 0);
        // The feed was published in one burst, so every subscription's oldest message is the same
        // age to within milliseconds — no subscriber is behind another, and none is marked.
        // SubscriberLagTest covers the case where one clearly is.
        assertNull(detail.furthestBehind(), "subscriptions a few milliseconds apart were told apart");
    }

    @Test
    @DisplayName("a search reports every subscription, and finds the message in each one still holding it")
    void findsWhichSubscriptionsHoldAMessage()
    {
        SubscriptionSearch found = addresses.find(detail(), "region = 'eu' AND AMQPriority = 4");

        assertEquals(4, found.rows().size(), "every subscription is reported, found or not");
        assertEquals(3, found.subscriptionsHolding(), "filtered, abandoned and shared all hold it");
        SubscriptionSearch.Row nonDurable = found.rows().stream()
                .filter(row -> row.subscription().kind() == Kind.NON_DURABLE).findFirst().orElseThrow();
        assertFalse(nonDurable.found(), "its filter takes only region = 'us'");
    }

    @Test
    @DisplayName("a message in flight to a consumer cannot be browsed, and the result says so instead of 'not here'")
    void saysWhenInFlightMessagesCouldNotBeSearched() throws Exception
    {
        Connection inflight = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
        try
        {
            inflight.setClientID("it-inflight");
            inflight.start();
            Session session = inflight.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            Topic topic = session.createTopic("it-inflight");
            MessageConsumer consumer = session.createDurableSubscriber(topic, "sub");
            MessageProducer producer = session.createProducer(topic);
            for (int n = 1; n <= 3; n++)
            {
                Message message = session.createTextMessage("n" + n);
                message.setIntProperty("n", n);
                producer.send(message);
            }
            // Received and never acknowledged: on the queue, delivering, and invisible to browse.
            for (int n = 1; n <= 3; n++)
            {
                assertNotNull(consumer.receive(5000));
            }

            AddressDetail detail = addresses.detail("it-inflight");
            Subscription subscription = detail.subscriptions().get(0);
            assertEquals(3, subscription.deliveringCount());
            assertNull(detail.oldestUndelivered(subscription), "the broker reports no age for in-flight messages");

            SubscriptionSearch.Row row = addresses.find(detail, "n = 2").rows().get(0);
            assertFalse(row.found(), "browse cannot see a delivered, unacknowledged message");
            assertTrue(row.unsearchable());
            assertTrue(row.verdict().contains("3 in flight"), row.verdict());
        }
        finally
        {
            // Closing without acknowledging returns the messages; nothing here consumed them.
            inflight.close();
        }
    }

    @Test
    @DisplayName("the address's settings and its exclusive divert are read from the broker")
    void readsWhereElseMessagesGo()
    {
        AddressRouting routing = detail().routing();

        assertNotNull(routing.settings(), routing.settingsError());
        assertEquals("DLQ", routing.settings().deadLetterAddress());
        assertTrue(routing.exists("DLQ"), "a stock broker has its DLQ");
        assertEquals(1, routing.divertsFrom().size(), routing.divertsFrom().toString());
        Divert divert = routing.divertsFrom().get(0);
        assertEquals(ArtemisBrokerSupport.FEED_DIVERT, divert.name());
        assertTrue(divert.exclusive());
        assertEquals("region = 'us'", divert.filter());
        assertEquals(ArtemisBrokerSupport.FEED_ADDRESS + ".us", divert.forwardingAddress());
        assertTrue(routing.hasExclusiveDivert());
    }

    @Test
    @DisplayName("diagnose names the abandoned subscription and the exclusive divert on a real broker")
    void diagnosesSubscriptionsAndDiverts()
    {
        QueueDirectory queues = new QueueDirectory(brokerSession);
        QueueBrowseService browse = new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L);
        List<Finding> findings = new StuckDiagnosisService(queues, new AddressDirectory(brokerSession, queues),
                new BrokerInfoService(brokerSession), browse, new DivertDirectory(brokerSession)).diagnose(false);

        assertTrue(findings.stream().anyMatch(finding -> ArtemisBrokerSupport.FEED_ABANDONED.equals(finding.queue())
                && finding.title().startsWith("Durable subscription")), findings.toString());
        assertTrue(
                findings.stream().anyMatch(
                        finding -> finding.title().contains("'" + ArtemisBrokerSupport.FEED_DIVERT + "' takes")),
                findings.toString());
    }

    @Test
    @DisplayName("an address the broker does not have is null, not an empty page")
    void unknownAddressIsNull()
    {
        assertNull(addresses.detail("it-no-such-address"));
    }

    private static AddressDetail detail()
    {
        return addresses.detail(ArtemisBrokerSupport.FEED_ADDRESS);
    }

    private static Map<String, Subscription> byName(AddressDetail detail)
    {
        return detail.subscriptions().stream().collect(Collectors.toMap(Subscription::name, Function.identity()));
    }
}
