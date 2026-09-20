package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code listQueues} answers a JSON document in which every value — counters included — is a quoted string, and in
 * which one field is spelled {@code messagesAcked} rather than the {@code messagesAcknowledged} the record calls it.
 * Getting either wrong shows a zero rather than failing.
 */
class QueueDirectoryTest
{

    private static final String NO_FILTER = "{\"field\":\"\",\"operation\":\"\",\"value\":\"\"}";

    private BrokerSession brokerSession;
    private ManagementChannel management;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        given(brokerSession.requireManagement()).willReturn(management);
        given(management.replyQueueName()).willReturn("tmp-reply-9f2c");
    }

    @Test
    @DisplayName("counters arrive quoted and are read as numbers")
    void readsQuotedCounters()
    {
        listQueuesReturns(1, queue("orders", "orders", 12, 3));

        QueueOverview queue = new QueueDirectory(brokerSession).overview().get(0);

        assertEquals(12, queue.messageCount());
        assertEquals(3, queue.messagesAcked());
        assertTrue(queue.durable());
        assertFalse(queue.paused());
    }

    @Test
    @DisplayName("the acked counter is called messagesAcked, not messagesAcknowledged")
    void acknowledgedIsNotTheFieldName()
    {
        listQueuesReturns(1, "{\"name\":\"orders\",\"address\":\"orders\",\"messagesAcknowledged\":\"7\"}");

        assertEquals(0, new QueueDirectory(brokerSession).overview().get(0).messagesAcked());
    }

    @Test
    @DisplayName("this tool's own management reply queue is left out of the listing")
    void hidesOurOwnReplyQueue()
    {
        listQueuesReturns(1, queue("orders", "orders", 1, 0), queue("tmp-reply-9f2c", "tmp-reply-9f2c", 0, 0));

        List<String> names = names(new QueueDirectory(brokerSession).overview());

        assertEquals(List.of("orders"), names);
    }

    @Test
    @DisplayName("Artemis's internal queues are flagged, not hidden")
    void flagsInternalQueuesRatherThanHidingThem()
    {
        listQueuesReturns(1, queue("orders", "orders", 1, 0),
                "{\"name\":\"$.artemis.internal.sf.my-cluster\",\"address\":\"x\",\"internalQueue\":\"true\"}");

        List<QueueOverview> queues = new QueueDirectory(brokerSession).overview();

        assertEquals(2, queues.size());
        assertTrue(queues.get(0).internalQueue());
    }

    @Test
    @DisplayName("queues are sorted without case deciding the order")
    void sortsCaseInsensitively()
    {
        listQueuesReturns(1, queue("Zebra", "Zebra", 0, 0), queue("apples", "apples", 0, 0),
                queue("Bananas", "Bananas", 0, 0));

        assertEquals(List.of("apples", "Bananas", "Zebra"), names(new QueueDirectory(brokerSession).overview()));
    }

    @Test
    @DisplayName("a full page means there may be another; a short one ends it")
    void pagesUntilTheBrokerRunsOut()
    {
        String[] full = IntStream.range(0, 200).mapToObj(i -> queue("q" + i, "q" + i, 0, 0)).toArray(String[]::new);
        listQueuesReturns(1, full);
        listQueuesReturns(2, queue("last", "last", 0, 0));

        assertEquals(201, new QueueDirectory(brokerSession).overview().size());
        verify(management).invoke(ResourceNames.BROKER, "listQueues", NO_FILTER, 2, 200);
        verify(management, never()).invoke(ResourceNames.BROKER, "listQueues", NO_FILTER, 3, 200);
    }

    @Test
    @DisplayName("a multicast subscription browses under its FQQN")
    void statsCarryTheFqqn()
    {
        listQueuesReturns(1, queue("sub-a", "events", 5, 0));

        QueueStats stats = new QueueDirectory(brokerSession).stats("sub-a");

        assertEquals("events", stats.address());
        assertEquals("events::sub-a", stats.browseName());
    }

    @Test
    @DisplayName("stats for a queue the broker no longer lists are null, not an error")
    void statsForAMissingQueueAreNull()
    {
        listQueuesReturns(1, queue("orders", "orders", 0, 0));

        org.junit.jupiter.api.Assertions.assertNull(new QueueDirectory(brokerSession).stats("gone"));
    }

    @Test
    @DisplayName("a blank address falls back to the queue name rather than an empty FQQN")
    void blankAddressFallsBackToTheName()
    {
        listQueuesReturns(1, "{\"name\":\"orders\",\"address\":\"\",\"messageCount\":\"0\"}");

        assertEquals("orders", new QueueDirectory(brokerSession).overview().get(0).address());
    }

    @Test
    @DisplayName("a reply that is not the expected JSON is reported, not swallowed")
    void unreadableReplyFails()
    {
        given(management.invoke(ResourceNames.BROKER, "listQueues", NO_FILTER, 1, 200)).willReturn("not json at all");

        assertThrows(BrokerException.class, () -> new QueueDirectory(brokerSession).overview());
    }

    private void listQueuesReturns(int page, String... queues)
    {
        given(management.invoke(ResourceNames.BROKER, "listQueues", NO_FILTER, page, 200))
                .willReturn("{\"data\":[" + String.join(",", queues) + "],\"count\":" + queues.length + "}");
    }

    /** One row as listQueues writes it: every value a string, including the counters and the flags. */
    private String queue(String name, String address, long messageCount, long acked)
    {
        return "{\"name\":\"" + name + "\",\"address\":\"" + address + "\",\"routingType\":\"ANYCAST\","
                + "\"messageCount\":\"" + messageCount + "\",\"deliveringCount\":\"0\",\"scheduledCount\":\"0\","
                + "\"consumerCount\":\"1\",\"messagesAdded\":\"" + messageCount + "\",\"messagesAcked\":\"" + acked
                + "\",\"durable\":\"true\",\"paused\":\"false\",\"internalQueue\":\"false\"}";
    }

    private List<String> names(List<QueueOverview> queues)
    {
        return queues.stream().map(QueueOverview::name).collect(Collectors.toList());
    }
}
