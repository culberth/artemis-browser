package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.broker.MessageComparison.BodyMode;
import com.culberth.tools.artemisbrowser.broker.MessageComparison.Change;
import com.culberth.tools.artemisbrowser.broker.MessageComparison.Field;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Message comparison against messages on a real broker, on every supported version.
 *
 * <p>
 * Plain text, JSON, a bytes body, properties set with different types and one set empty, a body past the compare limit,
 * a message held in flight by a consumer that has not acknowledged it, and one consumed after it was picked. Each side
 * is read through the JMS browser exactly as the message page reads it, and every counter is the same afterwards.
 */
class MessageComparisonIT
{

    private static final String QUEUE = "it-cmp.a";
    private static final String OTHER = "it-cmp.b";
    private static final String HELD = "it-cmp.held";
    private static final String GONE = "it-cmp.gone";

    private static BrokerSession brokerSession;
    private static QueueDirectory queues;
    private static MessageComparisonService comparison;
    /** Kept open until the end: closing the factory closes its connections and would release the held message. */
    private static ActiveMQConnectionFactory holderFactory;
    private static Connection holder;

    private static String plainA;
    private static String plainB;
    private static String jsonA;
    private static String jsonB;
    private static String bytes;
    private static String longA;
    private static String longB;
    private static String held;
    private static String gone;

    @BeforeAll
    static void sendSomeMessages() throws Exception
    {
        String url = ArtemisBrokerSupport.start();
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
                Connection connection = factory.createConnection(ArtemisBrokerSupport.USER,
                        ArtemisBrokerSupport.PASSWORD))
        {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(QUEUE);
            MessageProducer producer = session.createProducer(null);

            TextMessage message = session.createTextMessage("line one\nline two\nline three");
            message.setStringProperty("code", "5");
            message.setStringProperty("note", "");
            message.setStringProperty("onlyHere", "a");
            producer.send(queue, message);
            plainA = message.getJMSMessageID();

            message = session.createTextMessage("line one\nline TWO\nline three");
            message.setIntProperty("code", 5);
            producer.send(queue, message, jakarta.jms.DeliveryMode.PERSISTENT, 7, 0);
            plainB = message.getJMSMessageID();

            message = session.createTextMessage("{\"order\":1,\"items\":[1,2],\"note\":\"\"}");
            producer.send(queue, message);
            jsonA = message.getJMSMessageID();

            message = session.createTextMessage("{\"items\":[1,3],\"order\":1,\"note\":null}");
            producer.send(session.createQueue(OTHER), message);
            jsonB = message.getJMSMessageID();

            BytesMessage blob = session.createBytesMessage();
            blob.writeBytes(new byte[]
            { 1, 2, 3, 4
            });
            producer.send(queue, blob);
            bytes = blob.getJMSMessageID();

            message = session.createTextMessage("x".repeat(3000) + "A");
            producer.send(queue, message);
            longA = message.getJMSMessageID();
            message = session.createTextMessage("x".repeat(3000) + "B");
            producer.send(queue, message);
            longB = message.getJMSMessageID();

            message = session.createTextMessage("gone soon");
            producer.send(session.createQueue(GONE), message);
            gone = message.getJMSMessageID();
        }

        holderFactory = new ActiveMQConnectionFactory(url);
        holder = holderFactory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
        holder.start();
        Session holding = holder.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        Queue heldQueue = holding.createQueue(HELD);
        TextMessage message = holding.createTextMessage("in flight");
        holding.createProducer(heldQueue).send(message);
        held = message.getJMSMessageID();
        MessageConsumer consumer = holding.createConsumer(heldQueue);
        assertNotNull(consumer.receive(5000), "the held message was not delivered");

        brokerSession = ArtemisBrokerSupport.connect();
        queues = new QueueDirectory(brokerSession);
        comparison = new MessageComparisonService(queues,
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L), 1000, 1_000_000, 2000, 5000);
    }

    @AfterAll
    static void close() throws Exception
    {
        if (brokerSession != null)
        {
            brokerSession.close();
        }
        if (holder != null)
        {
            holder.close();
        }
        if (holderFactory != null)
        {
            holderFactory.close();
        }
    }

    @Test
    @DisplayName("two text messages: priority, a property typed differently, empty versus absent, and a changed line")
    void comparesText()
    {
        MessageComparison result = comparison.compare(QUEUE, plainA, QUEUE, plainB);

        assertTrue(result.compared(), result.left().unavailable() + " / " + result.right().unavailable());
        assertEquals(Change.DIFFERENT, field(result.headers(), "Priority").change());
        assertEquals(Change.SAME, field(result.headers(), "Type").change());
        Field code = field(result.properties(), "code");
        assertEquals(Change.DIFFERENT, code.change(), "string \"5\" and integer 5 are not the same property value");
        assertEquals("String", code.leftType());
        assertEquals("Integer", code.rightType());
        Field note = field(result.properties(), "note");
        assertEquals(Change.ONLY_LEFT, note.change());
        assertEquals("", note.left());
        assertEquals(Change.ONLY_LEFT, field(result.properties(), "onlyHere").change());
        assertEquals(BodyMode.TEXT, result.body().mode());
        assertEquals(List.of("same line one", "removed line two", "added line TWO", "same line three"),
                result.body().lines().stream().map(line -> line.op() + " " + line.text()).toList());
    }

    @Test
    @DisplayName("two JSON messages on different queues are compared by path")
    void comparesJsonAcrossQueues()
    {
        MessageComparison result = comparison.compare(QUEUE, jsonA, OTHER, jsonB);

        assertTrue(result.compared());
        assertEquals(BodyMode.JSON, result.body().mode());
        Map<String, Field> paths = result.body().paths().stream().collect(Collectors.toMap(Field::name, path -> path));
        assertEquals(Map.of("$.items[1]", Change.DIFFERENT, "$.note", Change.DIFFERENT),
                paths.values().stream().collect(Collectors.toMap(Field::name, Field::change)));
        assertEquals("string", paths.get("$.note").leftType());
        assertEquals("null", paths.get("$.note").right());
        assertEquals(Change.DIFFERENT, field(result.headers(), "Destination").change());
    }

    @Test
    @DisplayName("a bytes body is not compared, and bodies past the limit are cut and labelled")
    void unsupportedAndTruncated()
    {
        MessageComparison mixed = comparison.compare(QUEUE, plainA, QUEUE, bytes);
        assertTrue(mixed.compared());
        assertEquals(BodyMode.NOT_COMPARED, mixed.body().mode());

        MessageComparison long_ = comparison.compare(QUEUE, longA, QUEUE, longB);
        assertTrue(long_.body().identical(), "the first 1000 characters are the same");
        assertFalse(long_.body().limitations().isEmpty());
        assertTrue(long_.body().limitations().get(0).contains("first 1000 characters of each body"));
    }

    @Test
    @DisplayName("an in-flight message is unavailable, its body never read, and a consumed one is not an empty one")
    void unavailableSides() throws Exception
    {
        MessageComparison inFlight = comparison.compare(QUEUE, plainA, HELD, held);
        assertFalse(inFlight.compared());
        assertNull(inFlight.right().message());
        assertTrue(inFlight.right().unavailable().contains("1 in flight to a consumer"),
                inFlight.right().unavailable());

        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url());
                Connection connection = factory.createConnection(ArtemisBrokerSupport.USER,
                        ArtemisBrokerSupport.PASSWORD))
        {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            try (MessageConsumer consumer = session.createConsumer(session.createQueue(GONE)))
            {
                Message received = consumer.receive(5000);
                assertNotNull(received);
                assertEquals(gone, received.getJMSMessageID());
            }
        }
        MessageComparison consumed = comparison.compare(GONE, gone, QUEUE, plainA);
        assertFalse(consumed.compared());
        assertTrue(consumed.left().unavailable().contains("not an empty message"), consumed.left().unavailable());
        assertTrue(consumed.right().available(), "the other side is still read");
    }

    @Test
    @DisplayName("comparing consumes nothing")
    void consumesNothing()
    {
        Map<String, String> before = counters();
        for (int round = 0; round < 3; round++)
        {
            comparison.compare(QUEUE, plainA, QUEUE, plainB);
            comparison.compare(QUEUE, jsonA, OTHER, jsonB);
            comparison.compare(QUEUE, bytes, HELD, held);
        }
        assertEquals(before, counters());
    }

    private static Map<String, String> counters()
    {
        return queues.overview().stream().filter(q -> q.name().startsWith("it-cmp"))
                .collect(Collectors.toMap(QueueOverview::name, q -> q.messageCount() + "/" + q.deliveringCount() + "/"
                        + q.messagesAcked() + "/" + q.messagesAdded() + "/" + q.messagesKilled()));
    }

    private static Field field(List<Field> fields, String name)
    {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " in " + fields));
    }
}
