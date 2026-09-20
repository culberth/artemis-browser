package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @BeforeAll
    static void connect() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        queues = new QueueDirectory(brokerSession);
        browse = new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L);
        search = new MessageSearchService(brokerSession, queues, browse, 50);
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
