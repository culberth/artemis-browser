package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Artemis's filtered {@code countMessages} examines only the first {@code management-browse-page-size} messages (200 by
 * default). Anything that pages or chooses queues by it stops short of matches that are there — silently. These run
 * against a real broker because a mock would only return whatever count it was told to.
 */
class FilteredPagingIT
{

    /** 500 messages, every other one {@code region = 'eu'}: 250 matches, most of them past the 200-message sample. */
    private static final String DEEP = "it-deep";
    /** 400 messages, only the 350th carrying {@code marker = 1}: a filtered count of it is zero. */
    private static final String DEEP_ONLY = "it-deep-only";

    private static BrokerSession brokerSession;
    private static QueueBrowseService browse;

    @BeforeAll
    static void seed() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        browse = new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L);
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url()))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            try
            {
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                try (MessageProducer producer = session.createProducer(session.createQueue(DEEP)))
                {
                    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
                    for (int i = 0; i < 500; i++)
                    {
                        TextMessage message = session.createTextMessage("deep-" + i);
                        message.setStringProperty("region", i % 2 == 0 ? "eu" : "us");
                        producer.send(message);
                    }
                }
                try (MessageProducer producer = session.createProducer(session.createQueue(DEEP_ONLY)))
                {
                    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
                    for (int i = 1; i <= 400; i++)
                    {
                        TextMessage message = session.createTextMessage("only-" + i);
                        message.setIntProperty("marker", i == 350 ? 1 : 0);
                        producer.send(message);
                    }
                }
            }
            finally
            {
                connection.close();
            }
        }
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
    @DisplayName("a filter matching past the count's 200-message sample pages all the way through")
    void pagesPastTheCountingSample()
    {
        String filter = "region = 'eu'";

        MessagePage fourth = browse.page(DEEP, filter, 4, 50);
        MessagePage fifth = browse.page(DEEP, filter, 5, 50);
        MessagePage sixth = browse.page(DEEP, filter, 6, 50);

        assertFalse(fourth.totalKnown(), "a filtered count would have said 100 here");
        assertEquals(50, fourth.messages().size());
        assertTrue(fourth.hasNext());
        assertEquals(50, fifth.messages().size(), "matches 201-250 are reachable");
        assertFalse(fifth.hasNext(), "250 matches end exactly on page 5");
        assertTrue(sixth.messages().isEmpty());
    }

    @Test
    @DisplayName("export of a search includes a queue whose only match lies past the counting sample")
    void exportFindsAQueueTheCountMissed()
    {
        MessageSearchService search = new MessageSearchService(new QueueDirectory(brokerSession), browse, 50);

        SearchResult forExport = search.counts("marker = 1", false);

        assertTrue(forExport.matches().stream().anyMatch(match -> DEEP_ONLY.equals(match.queueName())),
                "left out of the export: " + forExport.matches());
    }

    @Test
    @DisplayName("an unfiltered page's total is what browse can reach")
    void unfilteredTotalIsWaitingMessages()
    {
        MessagePage page = browse.page(DEEP, null, 10, 50);

        assertEquals(500, page.totalMatching());
        assertEquals(50, page.messages().size());
        assertFalse(page.hasNext());
    }
}
