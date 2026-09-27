package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.broker.Subscription.Kind;
import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.Topic;
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
                new BrokerInfoService(brokerSession));

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
