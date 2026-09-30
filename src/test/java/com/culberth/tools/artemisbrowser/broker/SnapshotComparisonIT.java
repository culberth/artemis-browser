package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.compare.SnapshotComparer;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.CounterStatus;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.FieldChange;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.Presence;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.QueueChange;
import com.culberth.tools.artemisbrowser.compare.SnapshotFile;
import com.culberth.tools.artemisbrowser.compare.SnapshotReader;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two snapshots of a real broker, written out as the download is and read back as an upload is, with known changes in
 * between: messages sent to one queue, another queue made, a third deleted and made again. The comparison must find
 * each for what it is — traffic, a new queue, a recreated one — and confirm the broker by its node id and its unbroken
 * run by {@code uptimeMillis}, the attribute this phase added to the snapshot.
 */
class SnapshotComparisonIT
{

    private static final String GROWS = "it-cmp-grows";
    private static final String NEW = "it-cmp-new";
    private static final String REMADE = "it-cmp-remade";

    private static BrokerSession brokerSession;
    private static ActiveMQConnectionFactory factory;
    private static Connection connection;
    private static Session session;
    private static SnapshotComparison result;

    @BeforeAll
    static void snapshotAroundChanges() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url());
        connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
        connection.start();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        create(GROWS);
        create(REMADE);
        send(GROWS, 2);
        send(REMADE, 4);

        SnapshotService service = service();
        String before = json(service.collect());
        send(GROWS, 5);
        create(NEW);
        manage("destroyQueue", REMADE, true);
        create(REMADE);
        send(REMADE, 1);
        String after = json(service.collect());

        SnapshotReader reader = new SnapshotReader(64L << 20, 50_000);
        // Given latest first, as a person might.
        result = SnapshotComparer.compare(read(reader, "after.json", after), read(reader, "before.json", before));
    }

    @AfterAll
    static void cleanUp() throws Exception
    {
        for (String queue : new String[]
        { GROWS, NEW, REMADE
        })
        {
            try
            {
                manage("destroyQueue", queue, true);
            }
            catch (IllegalStateException ignored)
            {
                // Already gone.
            }
        }
        if (connection != null)
        {
            connection.close();
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
    @DisplayName("the same broker by node id, an unbroken run by uptimeMillis, and the files put in order")
    void identityAndContinuity()
    {
        assertTrue(result.identity().confirmed(), result.identity().basis());
        assertTrue(result.before().uptimeMillis().isNumber(),
                "uptimeMillis is a whole number on this broker: " + result.before().uptimeMillis().display());
        assertTrue(result.continuity().continuous(), result.continuity().explanation());
        assertTrue(result.reordered());
    }

    @Test
    @DisplayName("sent messages are a depth change and counted traffic")
    void traffic()
    {
        QueueChange grows = queue(GROWS);
        assertEquals(Presence.CHANGED, grows.presence());
        assertEquals("+5", field(grows, "messageCount", true).delta());
        assertEquals(CounterStatus.COUNTED, grows.counterStatus());
        assertEquals("+5", field(grows, "messagesAdded", false).delta());
    }

    @Test
    @DisplayName("a queue made in between is added; one deleted and made again is recreated, not traffic")
    void addedAndRecreated()
    {
        assertEquals(Presence.ADDED, queue(NEW).presence());
        QueueChange remade = queue(REMADE);
        assertEquals(Presence.RECREATED, remade.presence());
        assertEquals(CounterStatus.RECREATED, remade.counterStatus());
        assertTrue(field(remade, "messagesAdded", false).counterText(remade.counterStatus()).endsWith("(not traffic)"));
    }

    private static QueueChange queue(String name)
    {
        return result.queues().rows().stream().filter(q -> q.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not among " + result.queues().rows()));
    }

    private static FieldChange field(QueueChange queue, String name, boolean level)
    {
        return (level ? queue.levels() : queue.counters()).stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow();
    }

    private static SnapshotService service()
    {
        QueueDirectory queues = new QueueDirectory(brokerSession);
        BrokerInfoService info = new BrokerInfoService(brokerSession);
        AddressDirectory addresses = new AddressDirectory(brokerSession, queues);
        RateService rates = new RateService(brokerSession, new RateTracker(), queues);
        StuckDiagnosisService diagnosis = new StuckDiagnosisService(queues, addresses, info,
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L),
                new DivertDirectory(brokerSession), new InFlightService(brokerSession, info, 5000), rates,
                new ConnectivityService(brokerSession), new TransactionService(brokerSession, 100));
        return new SnapshotService(brokerSession, info, queues, addresses, rates, diagnosis,
                new ConnectivityService(brokerSession), new TransactionService(brokerSession, 100),
                new PermissionService(brokerSession), 1000, 200, 200);
    }

    private static String json(IncidentSnapshot snapshot)
    {
        StringWriter out = new StringWriter();
        SnapshotWriter.writeJson(out, snapshot);
        return out.toString();
    }

    private static SnapshotFile read(SnapshotReader reader, String label, String json)
    {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return reader.read(label, new ByteArrayInputStream(bytes), bytes.length);
    }

    private static void create(String queue) throws Exception
    {
        manage("createQueue", "{\"name\":\"" + queue + "\",\"address\":\"" + queue + "\",\"routing-type\":\"ANYCAST\"}",
                true);
    }

    private static void send(String queue, int count) throws Exception
    {
        try (MessageProducer producer = session.createProducer(session.createQueue(queue)))
        {
            for (int i = 0; i < count; i++)
            {
                producer.send(session.createTextMessage("cmp-" + i));
            }
        }
    }

    /** Test setup through the management address directly; the app's own channel refuses anything but a read. */
    private static void manage(String operation, Object... params) throws Exception
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
