package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The broker's own attributes come back in mixed types — {@code version} a String, {@code connectionCount} a Long,
 * {@code diskStoreUsage} a 0..1 Double, {@code status} a JSON document inside a String — and the JSON listings quote
 * some numbers but not others. Reading any of them the wrong way produces a believable figure, which is how a disk at
 * 85% once displayed as 0.10%.
 */
class BrokerInfoServiceTest
{

    private static final String REPLY_QUEUE = "tmp-reply-9f2c";

    private BrokerSession brokerSession;
    private ManagementChannel management;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        given(brokerSession.requireManagement()).willReturn(management);
        given(management.replyQueueName()).willReturn(REPLY_QUEUE);
    }

    @Test
    @DisplayName("diskStoreUsage is a 0..1 ratio and becomes a percentage")
    void scalesTheDiskRatioToAPercentage()
    {
        healthAttributes(0.1034d, 90L);

        BrokerHealth health = new BrokerInfoService(brokerSession).health();

        assertEquals(10.34d, health.diskUsedPercent(), 0.0001d);
        assertEquals(90L, health.maxDiskPercent());
        assertFalse(health.diskPressure());
    }

    @Test
    @DisplayName("disk pressure trips before the broker starts blocking producers")
    void reportsDiskPressureBeforeTheThreshold()
    {
        healthAttributes(0.85d, 90L);

        assertTrue(new BrokerInfoService(brokerSession).health().diskPressure());
    }

    @Test
    @DisplayName("state and node id are dug out of the status JSON, which arrives as a string")
    void readsTheStatusDocument()
    {
        healthAttributes(0.1d, 90L);

        BrokerHealth health = new BrokerInfoService(brokerSession).health();

        assertEquals("STARTED", health.state());
        assertEquals("2f1b0c44-aaaa-bbbb-cccc-1234567890ab", health.nodeId());
        assertTrue(health.running());
    }

    @Test
    @DisplayName("an unreadable status leaves the state unknown instead of failing the page")
    void survivesAnUnreadableStatus()
    {
        healthAttributes(0.1d, 90L);
        given(management.attribute(ResourceNames.BROKER, "status")).willReturn("{oops");

        BrokerHealth health = new BrokerInfoService(brokerSession).health();

        assertEquals("UNKNOWN", health.state());
        assertFalse(health.running());
    }

    @Test
    @DisplayName("counts arrive as Longs despite their int getters")
    void readsCountsOfEitherType()
    {
        healthAttributes(0.1d, 90L);

        BrokerHealth health = new BrokerInfoService(brokerSession).health();

        assertEquals(3L, health.connectionCount());
        assertEquals(4L, health.sessionCount());
        assertEquals(2L, health.consumerCount());
        assertEquals(1048576L, health.memoryUsedBytes());
    }

    @Test
    @DisplayName("an acceptor's host, port and protocols live under params")
    void readsAcceptorParams()
    {
        given(management.invoke(ResourceNames.BROKER, "getAcceptorsAsJSON")).willReturn("[{\"name\":\"artemis\","
                + "\"factoryClassName\":\"x\",\"params\":{\"protocols\":\"CORE,AMQP\",\"host\":\"0.0.0.0\","
                + "\"port\":\"61616\"}}]");

        AcceptorInfo acceptor = new BrokerInfoService(brokerSession).acceptors().get(0);

        assertEquals("artemis", acceptor.name());
        assertEquals("CORE,AMQP", acceptor.protocols());
        assertEquals(61616, acceptor.port());
    }

    @Test
    @DisplayName("an acceptor with no params block does not blow up the page")
    void survivesAnAcceptorWithoutParams()
    {
        given(management.invoke(ResourceNames.BROKER, "getAcceptorsAsJSON")).willReturn("[{\"name\":\"in-vm\"}]");

        AcceptorInfo acceptor = new BrokerInfoService(brokerSession).acceptors().get(0);

        assertEquals("in-vm", acceptor.name());
        assertEquals(0, acceptor.port());
    }

    @Test
    @DisplayName("our own connection is labelled, found through our own reply consumer")
    void labelsOurOwnConnection()
    {
        consumersReturn(consumer("c-1", REPLY_QUEUE, "conn-ours"), consumer("c-2", "orders", "conn-theirs"));
        given(management.invoke(ResourceNames.BROKER, "listConnectionsAsJSON"))
                .willReturn("[{\"connectionID\":\"conn-ours\",\"clientAddress\":\"/127.0.0.1:5000\","
                        + "\"creationTime\":1758326400000,\"sessionCount\":\"1\"},"
                        + "{\"connectionID\":\"conn-theirs\",\"clientAddress\":\"/127.0.0.1:5001\","
                        + "\"creationTime\":1758326400000,\"sessionCount\":\"2\"}]");

        List<BrokerConnection> connections = new BrokerInfoService(brokerSession).connections();

        assertTrue(connections.get(0).self());
        assertFalse(connections.get(1).self());
        assertEquals(2, connections.get(1).sessionCount());
        assertFalse(connections.get(0).createdText().isBlank());
    }

    @Test
    @DisplayName("our own management reply consumer is labelled rather than hidden")
    void labelsOurOwnConsumer()
    {
        consumersReturn(consumer("c-1", REPLY_QUEUE, "conn-ours"), consumer("c-2", "orders", "conn-theirs"));

        List<BrokerConsumer> consumers = new BrokerInfoService(brokerSession).consumers();

        assertEquals(2, consumers.size());
        assertTrue(consumers.get(0).self());
        assertFalse(consumers.get(1).self());
    }

    @Test
    @DisplayName("a producer's counters are bare numbers where its creation time is quoted")
    void readsProducerCountersAndTimestamp()
    {
        consumersReturn(consumer("c-1", REPLY_QUEUE, "conn-ours"));
        given(management.invoke(ResourceNames.BROKER, "listProducersInfoAsJSON"))
                .willReturn("[{\"id\":\"7\",\"name\":\"p\",\"connectionID\":\"conn-theirs\",\"sessionID\":\"s\","
                        + "\"creationTime\":\"1758326400000\",\"destination\":\"orders\","
                        + "\"lastProducedMessageID\":\"ID:1\",\"msgSent\":12,\"msgSizeSent\":2048},"
                        + "{\"id\":\"8\",\"connectionID\":\"conn-ours\",\"creationTime\":\"1758326400000\","
                        + "\"destination\":\"activemq.management\",\"msgSent\":3,\"msgSizeSent\":90}]");

        List<BrokerProducer> producers = new BrokerInfoService(brokerSession).producers();

        assertEquals("orders", producers.get(0).address());
        assertEquals(12, producers.get(0).messagesSent());
        assertEquals(2048, producers.get(0).bytesSent());
        assertFalse(producers.get(0).createdText().isBlank());
        assertFalse(producers.get(0).self());
        assertTrue(producers.get(1).self());
    }

    @Test
    @DisplayName("a listing that is not an array is empty, not an exception")
    void emptyListingForANonArrayReply()
    {
        given(management.invoke(ResourceNames.BROKER, "getAcceptorsAsJSON")).willReturn("{}");

        assertTrue(new BrokerInfoService(brokerSession).acceptors().isEmpty());
    }

    private void healthAttributes(double diskRatio, long maxDisk)
    {
        given(management.attribute(ResourceNames.BROKER, "version")).willReturn("2.42.0");
        given(management.attribute(ResourceNames.BROKER, "uptime")).willReturn("3 days");
        given(management.attribute(ResourceNames.BROKER, "status"))
                .willReturn("{\"server\":{\"state\":\"STARTED\",\"nodeId\":\"2f1b0c44-aaaa-bbbb-cccc-1234567890ab\","
                        + "\"version\":\"2.42.0\"}}");
        given(management.attribute(ResourceNames.BROKER, "connectionCount")).willReturn(3L);
        given(management.attribute(ResourceNames.BROKER, "sessionCount")).willReturn(4L);
        given(management.attribute(ResourceNames.BROKER, "totalConsumerCount")).willReturn(2L);
        given(management.attribute(ResourceNames.BROKER, "addressMemoryUsage")).willReturn(1048576L);
        given(management.attribute(ResourceNames.BROKER, "addressMemoryUsagePercentage")).willReturn(5L);
        given(management.attribute(ResourceNames.BROKER, "diskStoreUsage")).willReturn(diskRatio);
        given(management.attribute(ResourceNames.BROKER, "maxDiskUsage")).willReturn(maxDisk);
    }

    private void consumersReturn(String... consumers)
    {
        given(management.invoke(ResourceNames.BROKER, "listAllConsumersAsJSON"))
                .willReturn("[" + String.join(",", consumers) + "]");
    }

    private String consumer(String id, String queueName, String connectionId)
    {
        return "{\"consumerID\":\"" + id + "\",\"queueName\":\"" + queueName + "\",\"connectionID\":\"" + connectionId
                + "\",\"browseOnly\":\"false\",\"deliveringCount\":\"0\",\"messagesDelivered\":\"5\","
                + "\"messagesAcknowledged\":\"5\",\"status\":\"OK\"}";
    }
}
