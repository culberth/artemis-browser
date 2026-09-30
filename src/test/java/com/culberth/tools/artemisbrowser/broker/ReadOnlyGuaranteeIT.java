package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The product's central claim, checked rather than remembered: browsing, searching and exporting leave the broker
 * exactly as they found it.
 *
 * <p>
 * This is the one guarantee that cannot be established by reading the code — "we never call {@code receive()}" is an
 * argument, not evidence, and a JMS browser that is subtly misused acknowledges messages without anything in this
 * codebase looking wrong. So every counter that would move if a message were consumed is snapshotted, every read path
 * is driven hard, and the counters are compared.
 */
class ReadOnlyGuaranteeIT
{

    private static BrokerSession brokerSession;
    private static QueueDirectory queues;
    private static QueueBrowseService browse;
    private static MessageSearchService search;
    private static InFlightService inFlight;

    @BeforeAll
    static void connect() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        queues = new QueueDirectory(brokerSession);
        browse = new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L);
        inFlight = new InFlightService(brokerSession, new BrokerInfoService(brokerSession), 5000);
        search = new MessageSearchService(queues, browse, inFlight, 50);
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
    @DisplayName("every read path leaves every counter where it found it")
    void readingChangesNothing() throws Exception
    {
        Map<String, String> before = counters();

        for (int round = 0; round < 3; round++)
        {
            readEverything();
        }

        assertEquals(before, counters(), "a read path moved a counter");
    }

    @Test
    @DisplayName("reading what a consumer holds unacknowledged moves no counter, on that queue or any other")
    void readingInFlightChangesNothing() throws Exception
    {
        String queue = "it-held";
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url()))
        {
            Connection holder = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            try
            {
                holder.start();
                Session session = holder.createSession(false, Session.CLIENT_ACKNOWLEDGE);
                try (MessageProducer producer = session.createProducer(session.createQueue(queue)))
                {
                    for (int i = 1; i <= 5; i++)
                    {
                        producer.send(session.createTextMessage("held-" + i));
                    }
                }
                // Two received and never acknowledged; the other three sit in the consumer's buffer,
                // which the broker counts as delivering too.
                MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
                jakarta.jms.Message first = consumer.receive(5000);
                assertTrue(first != null && consumer.receive(5000) != null);
                awaitDelivering(queue, 5);

                Map<String, String> before = counters();
                InFlight held = null;
                for (int round = 0; round < 3; round++)
                {
                    held = inFlight.inFlight(queues.stats(queue));
                    readEverything();
                }
                // A search by its ID finds the held message in flight, where browse alone cannot see it.
                SearchResult byId = search.search(first.getJMSMessageID(), false);
                assertEquals(0, byId.totalMatches(), "browse should not see a message in flight");
                assertEquals(1, byId.inFlight().size(), "the ID lookup did not find the message in flight");
                assertEquals(queue, byId.inFlight().get(0).queueName());
                assertEquals(first.getJMSMessageID(), byId.inFlight().get(0).message().messageId());

                // All five in flight: nothing for browse to page through, whatever countMessages says.
                MessagePage page = browse.page(queue, null, 1, 50);
                assertEquals(0, page.totalMatching(), "the pager counted messages browse cannot return");
                assertFalse(page.hasNext());

                // The oldest in flight is the first one sent, which is also the first received.
                assertEquals(first.getJMSMessageID(), inFlight.oldest(queue, 5).message().messageId());

                assertEquals(before, counters(), "reading the in-flight messages moved a counter");

                assertEquals(5, held.listedCount());
                assertEquals(1, held.consumers().size());
                assertTrue(held.consumers().get(0).identified(), held.consumers().get(0).consumerName());
                assertTrue(held.consumers().get(0).client() != null, "the holder was not matched to a client");
                assertEquals(5L, held.consumers().get(0).inTransit());
            }
            finally
            {
                holder.close();
            }
        }
    }

    @Test
    @DisplayName("diagnose names a consumer whose buffer holds everything while another on the queue gets nothing")
    void diagnosesAHoardingConsumer() throws Exception
    {
        String queue = "it-hoard";
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url()))
        {
            Connection hoarder = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            Connection starved = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            try
            {
                hoarder.setClientID("it-hoarder");
                hoarder.start();
                Session session = hoarder.createSession(false, Session.CLIENT_ACKNOWLEDGE);
                try (MessageProducer producer = session.createProducer(session.createQueue(queue)))
                {
                    for (int i = 1; i <= 20; i++)
                    {
                        producer.send(session.createTextMessage("hoarded-" + i));
                    }
                }
                // One received; the default window pulls the other nineteen into this client's buffer
                // before the second consumer exists, and it gets nothing.
                assertTrue(session.createConsumer(session.createQueue(queue)).receive(5000) != null);
                awaitDelivering(queue, 20);
                starved.start();
                starved.createSession(false, Session.CLIENT_ACKNOWLEDGE)
                        .createConsumer(starved.createSession(false, Session.CLIENT_ACKNOWLEDGE).createQueue(queue));

                Map<String, String> before = counters();
                List<Finding> findings = new StuckDiagnosisService(queues, new AddressDirectory(brokerSession, queues),
                        new BrokerInfoService(brokerSession), browse, new DivertDirectory(brokerSession), inFlight,
                        new RateService(brokerSession, new RateTracker(), new QueueDirectory(brokerSession)),
                        new ConnectivityService(brokerSession)).diagnose(false);
                assertEquals(before, counters(), "diagnosing moved a counter");

                Finding hoarding = findings.stream()
                        .filter(f -> queue.equals(f.queue()) && f.title().startsWith("One consumer holds")).findFirst()
                        .orElseThrow(() -> new AssertionError("no hoarding finding in " + findings));
                assertTrue(hoarding.detail().contains("Client 'it-hoarder'"), hoarding.detail());
                assertTrue(hoarding.detail().contains("holds 20"), hoarding.detail());
            }
            finally
            {
                starved.close();
                hoarder.close();
            }
        }
    }

    /** The consumer's buffer fills asynchronously after the first receive. */
    private void awaitDelivering(String queue, long expected) throws InterruptedException
    {
        for (int attempt = 0; attempt < 50 && queues.stats(queue).deliveringCount() < expected; attempt++)
        {
            Thread.sleep(100);
        }
        assertEquals(expected, queues.stats(queue).deliveringCount());
    }

    @Test
    @DisplayName("a text body too long for management browse still exports whole")
    void exportsWholeTextBodies()
    {
        MessageSummary listed = browse.page(ArtemisBrokerSupport.TEXT_QUEUE, null, 1, 10).messages().get(0);
        MessageSummary exported = browse
                .pageForExport(ArtemisBrokerSupport.TEXT_QUEUE, ArtemisBrokerSupport.TEXT_QUEUE, null, 1, 10).messages()
                .get(0);

        assertTrue(listed.bodyTruncated(), "the broker was expected to truncate a 1000-character body");
        assertFalse(exported.bodyTruncated(), "the export should have read the whole body over JMS");
        assertTrue(exported.bodyPreview().length() > 1000, "body was " + exported.bodyPreview().length() + " chars");
        assertFalse(exported.bodyPreview().contains(", + "), "the broker's truncation marker survived into the export");
    }

    @Test
    @DisplayName("a bytes message, which management browse has no body for, exports with one")
    void exportsNonTextBodies()
    {
        MessageSummary listed = browse.page(ArtemisBrokerSupport.BYTES_QUEUE, null, 1, 10).messages().get(0);
        MessageSummary exported = browse
                .pageForExport(ArtemisBrokerSupport.BYTES_QUEUE, ArtemisBrokerSupport.BYTES_QUEUE, null, 1, 10)
                .messages().get(0);

        assertEquals(QueueBrowseService.NO_TEXT_BODY, listed.bodyPreview());
        assertTrue(exported.bodyPreview().contains("blob-payload"), exported.bodyPreview());
    }

    @Test
    @DisplayName("a multicast subscription is only readable through its FQQN")
    void readsAMulticastSubscription()
    {
        QueueStats stats = queues.stats(ArtemisBrokerSupport.MULTICAST_QUEUE);

        assertEquals(ArtemisBrokerSupport.MULTICAST_ADDRESS, stats.address());
        assertEquals(ArtemisBrokerSupport.MULTICAST_ADDRESS + "::" + ArtemisBrokerSupport.MULTICAST_QUEUE,
                stats.browseName());

        MessageSummary exported = browse.pageForExport(stats.name(), stats.browseName(), null, 1, 10).messages().get(0);

        assertTrue(exported.bodyPreview().startsWith("fanned-out"), exported.bodyPreview());
    }

    @Test
    @DisplayName("a filter finds a message by its property, in Artemis core syntax")
    void searchesByProperty()
    {
        SearchResult result = search.search("orderNumber = 3", false);

        assertEquals(1, result.totalMatches());
        assertEquals(ArtemisBrokerSupport.TEXT_QUEUE, result.matches().get(0).queueName());
    }

    @Test
    @DisplayName("a JMS-style filter name matches nothing rather than failing, which is why the UI names the dialect")
    void jmsSelectorSyntaxSilentlyMatchesNothing()
    {
        // Not a bug to fix here — a property of Artemis's core filter parser, and the reason every
        // filter input in the UI says which dialect it wants.
        assertEquals(0, search.search("JMSPriority = 4", false).totalMatches());
        assertTrue(search.search("AMQPriority = 4", false).totalMatches() > 0);
    }

    /** Drives every read path the app has, hard enough that a destructive one would show up. */
    private void readEverything() throws Exception
    {
        for (QueueOverview queue : queues.overview())
        {
            QueueStats stats = queues.stats(queue.name());
            MessagePage page = browse.page(queue.name(), null, 1, 100);
            inFlight.inFlight(stats);
            browse.pageForExport(queue.name(), stats.browseName(), null, 1, 100);

            for (MessageSummary message : page.messages())
            {
                browse.detail(queue.name(), stats.browseName(), message.messageId());
            }

            StringWriter csv = new StringWriter();
            new MessageExporter().writeCsv(csv, queue.name(), page.messages());
        }
        search.search("AMQPriority >= 0", true);
        new BrokerInfoService(brokerSession).consumers();
        for (BrokerConnection connection : new BrokerInfoService(brokerSession).connections())
        {
            new ClientDirectory(brokerSession).find(null, connection.connectionId());
        }
        AddressDetailService addresses = new AddressDetailService(new AddressDirectory(brokerSession, queues), queues,
                new BrokerInfoService(brokerSession), browse, new DivertDirectory(brokerSession), inFlight);
        for (AddressOverview address : new AddressDirectory(brokerSession, queues).overview())
        {
            AddressDetail detail = addresses.detail(address.name());
            if (detail != null)
            {
                addresses.find(detail, "AMQPriority >= 0");
            }
        }
        new StuckDiagnosisService(queues, new AddressDirectory(brokerSession, queues),
                new BrokerInfoService(brokerSession), browse, new DivertDirectory(brokerSession), inFlight,
                new RateService(brokerSession, new RateTracker(), new QueueDirectory(brokerSession)),
                new ConnectivityService(brokerSession)).diagnose(true);
    }

    /**
     * Everything that moves when a message is consumed: the count itself, what is checked out to a consumer, and what
     * has been acknowledged. Delivering and acked are the telling ones — a browser that accidentally acknowledges
     * leaves messageCount alone for a while but moves those immediately.
     */
    private Map<String, String> counters()
    {
        Map<String, String> counters = new LinkedHashMap<>();
        List<QueueOverview> overview = queues.overview();
        for (QueueOverview queue : overview)
        {
            if (queue.name().startsWith("it-"))
            {
                counters.put(queue.name(), queue.messageCount() + "/" + queue.deliveringCount() + "/"
                        + queue.messagesAcked() + "/" + queue.messagesAdded());
            }
        }
        assertFalse(counters.isEmpty(), "no seeded queues found on the broker");
        return counters;
    }
}
