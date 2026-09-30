package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.filter.GuidedFilter;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the filter builder writes, run by the broker. The expressions are pinned in {@code GuidedFilterTest}; this is
 * whether each one finds the messages it says it finds, on every supported version — quoted strings, types, names that
 * need quoting, LIKE with the value's own wildcards, priority, durability and both ends of a time range — and that a
 * filter the broker cannot parse arrives as an {@link InvalidFilterException}, not as zero matches.
 */
class GuidedFilterIT
{

    private static final String QUEUE = "it-guided";

    private static BrokerSession brokerSession;
    private static QueueBrowseService browse;
    private static QueueDirectory queues;
    private static final long[] SENT = new long[4];

    @BeforeAll
    static void seed() throws Exception
    {
        String url = ArtemisBrokerSupport.start();
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            try
            {
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                MessageProducer producer = session.createProducer(session.createQueue(QUEUE));
                String[] names =
                { "O'Brien", "back\\slash", "5", "Zoë 50%_x"
                };
                for (int i = 0; i < 4; i++)
                {
                    TextMessage message = session.createTextMessage("m" + i);
                    message.setStringProperty("customer", names[i]);
                    message.setIntProperty("n", i);
                    message.setLongProperty("big", 10_000_000_000L + i);
                    message.setDoubleProperty("ratio", i + 0.5);
                    message.setBooleanProperty("flag", i % 2 == 0);
                    // Names a JMS client refuses, as an AMQP or STOMP sender could set them.
                    var core = ((ActiveMQMessage) message).getCoreMessage();
                    core.putStringProperty(SimpleString.of("order-id"), SimpleString.of("A-" + i));
                    core.putStringProperty(SimpleString.of("my.dotted"), SimpleString.of("d" + i));
                    core.putStringProperty(SimpleString.of("and"), SimpleString.of("w" + i));
                    if (i != 3)
                    {
                        message.setStringProperty("region", i == 0 ? "eu" : "us");
                    }
                    producer.setDeliveryMode(i % 2 == 0 ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT);
                    producer.setPriority(i + 3);
                    producer.send(message);
                    SENT[i] = message.getJMSTimestamp();
                    Thread.sleep(25);
                }
            }
            finally
            {
                connection.close();
            }
        }
        brokerSession = ArtemisBrokerSupport.connect();
        browse = new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L);
        queues = new QueueDirectory(brokerSession);
    }

    @AfterAll
    static void close()
    {
        if (brokerSession != null)
        {
            brokerSession.close();
        }
    }

    private static List<String> matched(GuidedFilter.Result built)
    {
        assertTrue(built.ok(), built.errors().toString());
        return browse.matching(QUEUE, built.expression(), 50).stream().map(MessageSummary::bodyPreview).sorted()
                .toList();
    }

    private static List<String> property(String name, String type, String operator, String value)
    {
        return matched(GuidedFilter.build(new GuidedFilter.Request(
                List.of(new GuidedFilter.Condition(name, type, operator, value)), "", "", "", "", "UTC", "ANY")));
    }

    private static List<String> headers(String min, String max, String from, String to, String durability)
    {
        return matched(GuidedFilter.build(new GuidedFilter.Request(List.of(), min, max, from, to, "UTC", durability)));
    }

    /** Local UTC date-time text for an epoch-milli instant, as the form would send it. */
    private static String utc(long millis)
    {
        return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDateTime().toString();
    }

    @Test
    @DisplayName("text: an apostrophe, a backslash and non-ASCII are found exactly as written")
    void text()
    {
        assertEquals(List.of("m0"), property("customer", "STRING", "EQUALS", "O'Brien"));
        assertEquals(List.of("m1"), property("customer", "STRING", "EQUALS", "back\\slash"));
        assertEquals(List.of("m3"), property("customer", "STRING", "EQUALS", "Zoë 50%_x"));
        assertEquals(List.of("m1", "m2"), property("region", "STRING", "NOT_EQUALS", "eu"));
    }

    @Test
    @DisplayName("text sent as '5' is found as text and not as the number 5")
    void typesAreStrict()
    {
        assertEquals(List.of("m2"), property("customer", "STRING", "EQUALS", "5"));
        assertEquals(List.of(), property("customer", "INTEGER", "EQUALS", "5"));
        assertEquals(List.of("m2"), property("n", "INTEGER", "EQUALS", "2"));
        assertEquals(List.of(), property("n", "STRING", "EQUALS", "2"));
        assertEquals(List.of("m2"), property("n", "DECIMAL", "EQUALS", "2.0"));
    }

    @Test
    @DisplayName("numbers compare as numbers, longs past the int range included, and booleans as booleans")
    void numbersAndBooleans()
    {
        assertEquals(List.of("m2", "m3"), property("n", "INTEGER", "GREATER_OR_EQUAL", "2"));
        assertEquals(List.of("m3"), property("big", "INTEGER", "EQUALS", "10000000003"));
        assertEquals(List.of("m0", "m1"), property("ratio", "DECIMAL", "LESS", "2"));
        assertEquals(List.of("m0", "m2"), property("flag", "BOOLEAN", "EQUALS", "true"));
    }

    @Test
    @DisplayName("starts with and contains treat the value's own % and _ literally")
    void likePatterns()
    {
        assertEquals(List.of("m3"), property("customer", "STRING", "STARTS_WITH", "Zo"));
        assertEquals(List.of("m3"), property("customer", "STRING", "CONTAINS", "50%_"));
        // Unescaped, "%0_%x%" would match "50%_x" (_ any one character, % any run); escaped it must not.
        assertEquals(List.of(), property("customer", "STRING", "CONTAINS", "0_%x"));
        assertEquals(List.of("m1"), property("customer", "STRING", "CONTAINS", "k\\s"));
    }

    @Test
    @DisplayName("names with a hyphen, a dot or a reserved word are quoted and found")
    void quotedNames()
    {
        assertEquals(List.of("m1"), property("order-id", "STRING", "EQUALS", "A-1"));
        assertEquals(List.of("m2"), property("my.dotted", "STRING", "EQUALS", "d2"));
        assertEquals(List.of("m3"), property("and", "STRING", "EQUALS", "w3"));
    }

    @Test
    @DisplayName("is set and is not set")
    void presence()
    {
        assertEquals(List.of("m3"), property("region", "STRING", "ABSENT", ""));
        assertEquals(List.of("m0", "m1", "m2"), property("region", "STRING", "PRESENT", ""));
    }

    @Test
    @DisplayName("priority and durability use the broker's own header names and values")
    void headersMatch()
    {
        assertEquals(List.of("m1"), headers("4", "4", "", "", "ANY"));
        assertEquals(List.of("m1", "m2"), headers("4", "5", "", "", "ANY"));
        assertEquals(List.of("m3"), headers("6", "", "", "", "ANY"));
        assertEquals(List.of("m0", "m2"), headers("", "", "", "", "DURABLE"));
        assertEquals(List.of("m1", "m3"), headers("", "", "", "", "NON_DURABLE"));
    }

    @Test
    @DisplayName("a time range includes a message sent at its start and excludes one sent at its end")
    void timestampBoundaries()
    {
        assertEquals(List.of("m1", "m2"), headers("", "", utc(SENT[1]), utc(SENT[3]), "ANY"));
        assertEquals(List.of("m1"), headers("", "", utc(SENT[1]), utc(SENT[2]), "ANY"));
        assertEquals(List.of("m0"), headers("", "", "", utc(SENT[1]), "ANY"));
        assertEquals(List.of("m3"), headers("", "", utc(SENT[3]), "", "ANY"));
    }

    @Test
    @DisplayName("the same wall-clock time in another zone is another instant")
    void zones()
    {
        String local = Instant.ofEpochMilli(SENT[2]).atZone(java.time.ZoneId.of("Asia/Tokyo")).toLocalDateTime()
                .toString();
        GuidedFilter.Result tokyo = GuidedFilter
                .build(new GuidedFilter.Request(List.of(), "", "", local, "", "Asia/Tokyo", "ANY"));
        assertEquals(List.of("m2", "m3"), matched(tokyo));
    }

    @Test
    @DisplayName("a filter the broker cannot parse is an InvalidFilterException naming the reason, not zero matches")
    void invalidFilter()
    {
        for (String bad : List.of("n == 2", "customer = 'unterminated", "my.dotted = 'd1'", "AND = 1"))
        {
            InvalidFilterException refused = assertThrows(InvalidFilterException.class,
                    () -> browse.matching(QUEUE, bad, 10), bad);
            assertTrue(refused.getMessage().contains("AMQ229020"), refused.getMessage());
            assertTrue(refused.getMessage().contains("not a result of zero matches"), refused.getMessage());
        }
        // The one mistake no error reveals: a bare hyphenated name parses as a subtraction.
        assertEquals(List.of(), browse.matching(QUEUE, "order-id = 'A-1'", 10));
    }

    @Test
    @DisplayName("running every built filter consumed nothing")
    void consumedNothing()
    {
        QueueStats before = queues.stats(QUEUE);
        text();
        likePatterns();
        headersMatch();
        timestampBoundaries();
        invalidFilter();
        QueueStats after = queues.stats(QUEUE);
        assertEquals(4, after.messageCount());
        assertEquals(Map.of("count", before.messageCount(), "acked", before.messagesAcknowledged()),
                Map.of("count", after.messageCount(), "acked", after.messagesAcknowledged()));
    }
}
