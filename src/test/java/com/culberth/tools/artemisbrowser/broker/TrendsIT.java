package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.broker.QueueTrend.Interval;
import com.culberth.tools.artemisbrowser.broker.QueueTrend.Kind;
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
 * A trend built from a real broker's counters, with the readings' clock supplied so the test need not wait out the
 * spacing: messages arrive, some are consumed, and the queue is deleted and made again under the same name — which must
 * show as a break, since its id changes and its counters start over.
 */
class TrendsIT
{

    private static final String QUEUE = "it-trend";
    private static final long T0 = 1_790_000_000_000L;
    private static final long S = 15_000;

    private static BrokerSession brokerSession;
    private static QueueDirectory queues;
    private static RateTracker tracker;
    private static ActiveMQConnectionFactory factory;
    private static Connection connection;
    private static Session session;
    private static long firstId;
    private static long secondId;

    @BeforeAll
    static void readThroughChanges() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        queues = new QueueDirectory(brokerSession);
        tracker = new RateTracker();
        factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url());
        connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
        connection.start();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        manage("createQueue", "{\"name\":\"" + QUEUE + "\",\"address\":\"" + QUEUE + "\",\"routing-type\":\"ANYCAST\"}",
                true);
        observe(0);
        send(10);
        observe(S);
        consume(4);
        observe(2 * S);
        firstId = find().id();

        manage("destroyQueue", QUEUE);
        manage("createQueue", "{\"name\":\"" + QUEUE + "\",\"address\":\"" + QUEUE + "\",\"routing-type\":\"ANYCAST\"}",
                true);
        send(2);
        observe(3 * S);
        secondId = find().id();
    }

    @AfterAll
    static void disconnect() throws Exception
    {
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
    @DisplayName("arrivals and acknowledgments show as separate rates, and the backlog's change is dated")
    void measuresArrivalsAndConsumption()
    {
        List<Interval> intervals = tracker.trends().of(QUEUE).intervals();

        Interval arriving = intervals.get(0);
        assertEquals(Kind.MEASURED, arriving.kind());
        assertEquals(10 / 15.0, arriving.inPerSecond(), 1e-9);
        assertEquals(0.0, arriving.ackedPerSecond());
        assertEquals(10, arriving.depthTo());

        Interval consuming = intervals.get(1);
        assertEquals(4 / 15.0, consuming.ackedPerSecond(), 1e-9);
        assertEquals(6, consuming.depthTo());
    }

    @Test
    @DisplayName("the queue made again is a break: a new id, and nothing compared across it")
    void recreationIsABreak()
    {
        assertTrue(firstId >= 0 && secondId >= 0, "the listing carries queue ids");
        assertNotEquals(firstId, secondId);

        QueueTrend trend = tracker.trends().of(QUEUE);
        assertEquals(Kind.RECREATED, trend.intervals().get(2).kind());
        assertNotNull(trend.lastBreak());
        assertEquals(2, trend.polylines(100, 20).size());
    }

    private static QueueOverview find()
    {
        return queues.overview().stream().filter(queue -> queue.name().equals(QUEUE)).findFirst().orElseThrow();
    }

    private static void observe(long offset)
    {
        // The uptime stands in for the broker's, moving with the supplied clock: no restart happens here.
        tracker.observe("it-broker", 3_600_000L + offset, queues.overview(), T0 + offset);
    }

    private static void send(int count) throws Exception
    {
        try (MessageProducer producer = session.createProducer(session.createQueue(QUEUE)))
        {
            for (int i = 0; i < count; i++)
            {
                producer.send(session.createTextMessage("trend-" + i));
            }
        }
    }

    private static void consume(int count) throws Exception
    {
        try (MessageConsumer consumer = session.createConsumer(session.createQueue(QUEUE)))
        {
            for (int i = 0; i < count; i++)
            {
                assertNotNull(consumer.receive(5000));
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
