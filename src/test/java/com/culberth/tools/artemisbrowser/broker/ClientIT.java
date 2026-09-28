package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import java.util.List;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The client chain — connections, their sessions, the consumers and producers on those — against a real broker, where
 * none of the listings alone connects a client id to what it consumes.
 */
class ClientIT
{

    private static final String CLIENT = "it-billing-svc";

    private static BrokerSession brokerSession;
    private static ActiveMQConnectionFactory factory;
    private static Connection named;
    private static Connection anonymous;

    @BeforeAll
    static void connectClients() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url());

        named = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
        named.setClientID(CLIENT);
        named.start();
        Session session = named.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        session.createProducer(session.createQueue("it-client-in")).send(session.createTextMessage("work"));
        MessageConsumer consumer = session.createConsumer(session.createQueue("it-client-in"));
        assertNotNull(consumer.receive(5000), "held unacknowledged: one in flight to this client");
        session.createProducer(session.createQueue("it-client-out")).send(session.createTextMessage("result"));

        anonymous = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
        anonymous.start();
        Session other = anonymous.createSession(false, Session.AUTO_ACKNOWLEDGE);
        other.createConsumer(other.createQueue("it-client-in"));
    }

    @AfterAll
    static void disconnect() throws Exception
    {
        named.close();
        anonymous.close();
        factory.close();
        brokerSession.close();
    }

    @Test
    @DisplayName("a client id reaches what that client consumes, holds in flight and sends — and only that")
    void findsANamedClient()
    {
        ClientView client = new ClientDirectory(brokerSession).find(CLIENT, null);

        assertNotNull(client);
        assertTrue(client.named());
        assertEquals(1, client.connections().size());
        assertEquals("CORE", client.connections().get(0).protocol());
        List<String> queues = client.consumers().stream().map(ClientView.Consumer::queue).toList();
        assertEquals(List.of("it-client-in"), queues, "the anonymous consumer on the same queue is someone else");
        assertEquals(1, client.inFlight());
        assertTrue(client.producers().stream().anyMatch(producer -> "it-client-out".equals(producer.address())),
                String.valueOf(client.producers()));
    }

    @Test
    @DisplayName("a client with no client id is found by its connection, the only handle it has")
    void findsAnAnonymousClientByConnection()
    {
        String connection = new BrokerInfoService(brokerSession).consumers().stream()
                .filter(consumer -> "it-client-in".equals(consumer.queueName())).map(BrokerConsumer::connectionId)
                .filter(id -> !id.equals(
                        new ClientDirectory(brokerSession).find(CLIENT, null).connections().get(0).connectionId()))
                .findFirst().orElseThrow();

        ClientView client = new ClientDirectory(brokerSession).find(null, connection);

        assertNotNull(client);
        assertFalse(client.named());
        assertEquals(List.of("it-client-in"), client.consumers().stream().map(ClientView.Consumer::queue).toList());
        assertNull(new ClientDirectory(brokerSession).find("it-nobody", null));
    }
}
