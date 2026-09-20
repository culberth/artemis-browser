package com.culberth.tools.artemisbrowser.broker;

import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import jakarta.jms.TopicSubscriber;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Artemis in a container, plus enough messages on it to exercise every read path: a text queue, a bytes queue,
 * and a multicast address whose subscription queue is not named after it.
 *
 * <p>
 * One container is started for the whole integration run and left to Ryuk to reap. Seeding is done over JMS rather than
 * by exec'ing the bundled CLI — same result, no dependency on the image's layout.
 */
final class ArtemisBrokerSupport
{

    static final String USER = "artemis";
    static final String PASSWORD = "artemis";

    static final String TEXT_QUEUE = "it-orders";
    static final String BYTES_QUEUE = "it-blobs";
    static final String MULTICAST_ADDRESS = "it-events";
    /** Artemis names a durable subscription's queue {@code clientId.subscriptionName}. */
    static final String MULTICAST_QUEUE = "it-client.it-sub";

    private static GenericContainer<?> container;

    private ArtemisBrokerSupport()
    {
    }

    static synchronized String start() throws JMSException
    {
        if (container == null)
        {
            container = new GenericContainer<>(DockerImageName.parse("apache/activemq-artemis:latest-alpine"))
                    .withEnv("ARTEMIS_USER", USER).withEnv("ARTEMIS_PASSWORD", PASSWORD).withExposedPorts(61616)
                    // "Server is now active" — not "live", which the log does not say.
                    .waitingFor(Wait.forLogMessage(".*Server is now active.*\\n", 1))
                    .withStartupTimeout(Duration.ofMinutes(4));
            container.start();
            seed(url());
        }
        return url();
    }

    static String url()
    {
        return "tcp://" + container.getHost() + ":" + container.getMappedPort(61616);
    }

    static BrokerCredentials credentials()
    {
        return new BrokerCredentials(container.getHost(), container.getMappedPort(61616), USER, PASSWORD);
    }

    static BrokerSession connect() throws JMSException
    {
        start();
        BrokerSession session = new BrokerSession(10000, 10000);
        session.connect(credentials());
        return session;
    }

    /**
     * Text messages long enough that the broker truncates them in a management browse, a bytes message that management
     * browse has no body for at all, and a multicast publication that only an FQQN browse can read.
     */
    private static void seed(String url) throws JMSException
    {
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url))
        {
            Connection connection = factory.createConnection(USER, PASSWORD);
            connection.setClientID("it-client");
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            try (MessageProducer producer = session.createProducer(session.createQueue(TEXT_QUEUE)))
            {
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                for (int i = 1; i <= 5; i++)
                {
                    TextMessage message = session.createTextMessage("order-" + i + " " + "x".repeat(1000));
                    message.setIntProperty("orderNumber", i);
                    producer.send(message);
                }
            }

            try (MessageProducer producer = session.createProducer(session.createQueue(BYTES_QUEUE)))
            {
                BytesMessage message = session.createBytesMessage();
                message.writeBytes("blob-payload".getBytes(StandardCharsets.UTF_8));
                producer.send(message);
            }

            // The durable subscription has to exist before anything is published, or the
            // publication has nowhere to be routed and is silently dropped.
            Topic topic = session.createTopic(MULTICAST_ADDRESS);
            TopicSubscriber subscriber = session.createDurableSubscriber(topic, "it-sub");
            try (MessageProducer producer = session.createProducer(topic))
            {
                producer.send(session.createTextMessage("fanned-out " + "y".repeat(500)));
            }
            subscriber.close();

            session.close();
            connection.close();
        }
    }
}
