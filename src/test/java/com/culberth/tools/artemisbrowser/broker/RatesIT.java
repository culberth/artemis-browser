package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rates against a real broker: the uptime the restart check depends on is read, and messages sent between two readings
 * show up as a rate.
 */
class RatesIT
{

    private static final String QUEUE = "it-rate";

    private static BrokerSession brokerSession;
    private static QueueDirectory queues;

    @BeforeAll
    static void connect() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        queues = new QueueDirectory(brokerSession);
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
    @DisplayName("messages sent between two readings are a rate in, and nothing acknowledged is zero out")
    void measuresARate() throws Exception
    {
        send(1);
        RateService rates = new RateService(brokerSession, new RateTracker(), queues);
        assertFalse(rates.observe(queues.overview()).measured(), "the first reading has nothing to compare with");

        send(20);
        Thread.sleep(2_200);
        Rates measured = rates.observe(queues.overview());

        assertTrue(measured.measured(), String.valueOf(measured.unavailable()));
        QueueRate rate = measured.of(QUEUE);
        assertNotNull(rate);
        double expected = 20 / (measured.intervalMillis() / 1000.0);
        assertEquals(expected, rate.inPerSecond(), 0.01);
        assertEquals(0.0, rate.ackedPerSecond());
    }

    @Test
    @DisplayName("diagnose with no earlier reading takes two itself, a few seconds apart, and says it did")
    void diagnoseSamplesWhenItHasNoReading()
    {
        Diagnosis.Measured measured = new RateService(brokerSession, new RateTracker(), queues)
                .forDiagnosis(queues.overview());

        assertTrue(measured.sampled());
        assertTrue(measured.rates().measured(), String.valueOf(measured.rates().unavailable()));
        assertTrue(measured.rates().intervalMillis() >= RateService.DIAGNOSE_SAMPLE_MILLIS);
    }

    private static void send(int count) throws Exception
    {
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url()))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            try
            {
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                try (MessageProducer producer = session.createProducer(session.createQueue(QUEUE)))
                {
                    for (int i = 0; i < count; i++)
                    {
                        producer.send(session.createTextMessage("rate-" + i));
                    }
                }
            }
            finally
            {
                connection.close();
            }
        }
    }
}
