package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The management {@code browse} reply is a CompositeData array whose values arrive in whatever type the broker felt
 * like, and whose {@code text} has already been truncated by the broker with the marker written into the value. Both
 * are silent failure modes: a wrong parse shows a plausible number, and a kept marker ships a quarter of a message as
 * if it were whole.
 */
class QueueBrowseServiceTest
{

    private static final String QUEUE = ResourceNames.QUEUE + "orders";

    private BrokerSession brokerSession;
    private ManagementChannel management;
    private Session jmsSession;
    private QueueBrowser jmsBrowser;

    @BeforeEach
    void mocks() throws Exception
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        jmsSession = mock(Session.class);
        jmsBrowser = mock(QueueBrowser.class);

        given(brokerSession.requireManagement()).willReturn(management);
        given(brokerSession.requireSession()).willReturn(jmsSession);
        given(jmsSession.createQueue(any())).willReturn(mock(Queue.class));
        given(jmsSession.createBrowser(any())).willReturn(jmsBrowser);
        given(jmsBrowser.getEnumeration()).willReturn(Collections.enumeration(List.of()));
    }

    @Test
    @DisplayName("browse answers a map keyed by a class name, not the array itself")
    void unwrapsTheCompositeDataMap()
    {
        browseReturns(message("ID:1", "hello"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);

        MessagePage page = service().page("orders", null, 1, 50);

        assertEquals(1, page.messages().size());
        assertEquals("hello", page.messages().get(0).bodyPreview());
        assertEquals(1L, page.totalMatching());
    }

    @Test
    @DisplayName("counters arrive as numbers or as strings and parse either way")
    void parsesMixedAttributeTypes()
    {
        Map<String, Object> values = new LinkedHashMap<>(message("ID:7", "body"));
        values.put("persistentSize", "512");
        values.put("priority", 9);
        values.put("durable", Boolean.TRUE);
        values.put("redelivered", "true");
        browseReturns(values);
        given(management.invoke(QUEUE, "countMessages", "")).willReturn("4");

        MessageSummary summary = service().page("orders", null, 1, 50).messages().get(0);

        assertEquals(512L, summary.sizeBytes());
        assertEquals(9, summary.priority());
        assertTrue(summary.persistent());
        assertTrue(summary.redelivered());
    }

    @Test
    @DisplayName("positions continue across pages rather than restarting at 1")
    void numbersPositionsFromTheStartOfTheResult()
    {
        browseReturns(message("ID:1", "a"), message("ID:2", "b"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(120L);
        given(management.invoke(QUEUE, "browse", 3, 50))
                .willReturn(browseReply(message("ID:1", "a"), message("ID:2", "b")));

        MessagePage page = service().page("orders", null, 3, 50);

        assertEquals(101, page.messages().get(0).position());
        assertEquals(102, page.messages().get(1).position());
    }

    @Test
    @DisplayName("a filter is passed to browse as well as to countMessages")
    void filterReachesBothCalls()
    {
        given(management.invoke(QUEUE, "countMessages", "AMQPriority > 4")).willReturn(1L);
        given(management.invoke(QUEUE, "browse", 1, 50, "AMQPriority > 4"))
                .willReturn(browseReply(message("ID:1", "urgent")));

        MessagePage page = service().page("orders", "  AMQPriority > 4  ", 1, 50);

        assertEquals("AMQPriority > 4", page.filter());
        assertEquals(1, page.messages().size());
        verify(management).invoke(QUEUE, "browse", 1, 50, "AMQPriority > 4");
    }

    @Test
    @DisplayName("the broker's own truncation marker is stripped and reported as truncation")
    void stripsTheBrokerTruncationMarker()
    {
        String body = "x".repeat(256);
        browseReturns(message("ID:1", body + ", + 744 more"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);

        MessageSummary summary = service(1000, 1000).page("orders", null, 1, 50).messages().get(0);

        assertEquals(body, summary.bodyPreview());
        assertTrue(summary.bodyTruncated());
    }

    @Test
    @DisplayName("a short body that merely reads like the marker is left alone")
    void doesNotMistakeAShortBodyForTruncation()
    {
        browseReturns(message("ID:1", "2 shipped, + 3 more"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);

        MessageSummary summary = service(1000, 1000).page("orders", null, 1, 50).messages().get(0);

        assertEquals("2 shipped, + 3 more", summary.bodyPreview());
        assertFalse(summary.bodyTruncated());
    }

    @Test
    @DisplayName("export replaces a broker-truncated body with the whole one from the JMS browser")
    void exportReadsWholeBodiesOverJms() throws Exception
    {
        String whole = "y".repeat(1000);
        browseReturns(message("ID:1", "y".repeat(256) + ", + 744 more"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);
        browserHolds(text("ID:1", whole));

        MessageSummary summary = service().pageForExport("orders", "orders", null, 1, 5000).messages().get(0);

        assertEquals(whole, summary.bodyPreview());
        assertFalse(summary.bodyTruncated());
    }

    @Test
    @DisplayName("export reads bodies management browse never had at all")
    void exportReadsNonTextBodies() throws Exception
    {
        browseReturns(nonTextMessage("ID:9"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);

        MapMessage map = mock(MapMessage.class);
        given(map.getJMSMessageID()).willReturn("ID:9");
        given(map.getMapNames()).willReturn(Collections.enumeration(List.of("orderId")));
        given(map.getObject("orderId")).willReturn("A-17");
        browserHolds(map);

        MessageSummary summary = service().pageForExport("orders", "orders", null, 1, 5000).messages().get(0);

        assertEquals("orderId = A-17\n", summary.bodyPreview());
        assertFalse(summary.bodyTruncated());
    }

    @Test
    @DisplayName("export opens no browser when every body already arrived whole")
    void exportSkipsTheJmsPassWhenNothingNeedsIt() throws Exception
    {
        browseReturns(message("ID:1", "short"), message("ID:2", "also short"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(2L);

        MessagePage page = service().pageForExport("orders", "orders", null, 1, 5000);

        assertEquals("short", page.messages().get(0).bodyPreview());
        verify(jmsSession, never()).createBrowser(any());
    }

    @Test
    @DisplayName("a message the JMS pass never reaches keeps its truncated body, still flagged")
    void exportFallsBackWhenTheBodyCannotBeRead() throws Exception
    {
        String head = "z".repeat(256);
        browseReturns(message("ID:1", head + ", + 744 more"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);
        browserHolds(text("ID:other", "someone else"));

        MessageSummary summary = service().pageForExport("orders", "orders", null, 1, 5000).messages().get(0);

        assertEquals(head, summary.bodyPreview());
        assertTrue(summary.bodyTruncated());
    }

    @Test
    @DisplayName("the JMS pass stops at the scan limit instead of walking a whole queue")
    void exportStopsAtTheScanLimit() throws Exception
    {
        browseReturns(message("ID:2", "w".repeat(256) + ", + 10 more"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);
        browserHolds(text("ID:1", "first"), text("ID:2", "the one we wanted"));

        QueueBrowseService service = new QueueBrowseService(brokerSession, 200, 200000, 1, 20_000_000L);
        MessageSummary summary = service.pageForExport("orders", "orders", null, 1, 5000).messages().get(0);

        assertEquals("w".repeat(256), summary.bodyPreview());
        assertTrue(summary.bodyTruncated());
    }

    @Test
    @DisplayName("export browses a multicast subscription by its FQQN, not its queue name")
    void exportBrowsesTheFqqn() throws Exception
    {
        browseReturns(nonTextMessage("ID:1"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);
        browserHolds(text("ID:1", "fanned out"));

        service().pageForExport("orders", "events::sub-a", null, 1, 5000);

        verify(jmsSession).createQueue("events::sub-a");
    }

    @Test
    @DisplayName("scheduled messages come from their own call, because browse does not return them")
    void readsScheduledMessages()
    {
        long soon = System.currentTimeMillis() + 3_600_000L;
        given(management.invoke(QUEUE, "listScheduledMessagesAsJSON"))
                .willReturn("[{\"address\":\"orders\",\"messageID\":66,\"type\":3,\"priority\":4,"
                        + "\"userID\":\"ID:9\",\"durable\":true,\"orderRef\":\"A-17\",\"attempt\":2,"
                        + "\"__AMQ_CID\":\"abc\",\"_AMQ_ROUTING_TYPE\":1,\"expiration\":0," + "\"_AMQ_SCHED_DELIVERY\":"
                        + soon + ",\"timestamp\":1789867473832}]");

        ScheduledMessage message = service().scheduled("orders").get(0);

        assertEquals("ID:9", message.messageId());
        assertEquals("Text", message.type());
        assertEquals(4, message.priority());
        assertTrue(message.durable());
        assertFalse(message.scheduledForText().isBlank());
        assertFalse(message.overdue());
        // The message's own properties sit at the top level beside the headers; only the former
        // belong to the producer, and Artemis's internal two belong to nobody.
        assertEquals(Map.of("orderRef", "A-17", "attempt", "2"), message.properties());
    }

    @Test
    @DisplayName("a delivery time already past is called out rather than shown as pending")
    void flagsOverdueScheduledMessages()
    {
        given(management.invoke(QUEUE, "listScheduledMessagesAsJSON"))
                .willReturn("[{\"messageID\":1,\"type\":3,\"userID\":\"ID:1\","
                        + "\"_AMQ_SCHED_DELIVERY\":1000,\"timestamp\":900}]");

        assertTrue(service().scheduled("orders").get(0).overdue());
    }

    @Test
    @DisplayName("a queue with nothing scheduled is empty, not an error")
    void handlesNoScheduledMessages()
    {
        given(management.invoke(QUEUE, "listScheduledMessagesAsJSON")).willReturn("[]");

        assertTrue(service().scheduled("orders").isEmpty());
    }

    @Test
    @DisplayName("properties are read from the typed tables, not parsed out of PropertiesText")
    void readsPropertiesFromTypedTables()
    {
        Map<String, Object> values = new LinkedHashMap<>(message("ID:1", "body"));
        values.put("StringProperties", propertyTable("String", Map.of("orderRef", "A-17")));
        values.put("IntProperties", propertyTable("Integer", Map.of("attempt", "3")));
        // Artemis's own, which a producer did not set and a filter is not written against.
        values.put("ByteProperties", propertyTable("Byte", Map.of("_AMQ_ROUTING_TYPE", "1")));
        values.put("PropertiesText", "{orderRef=A-17, attempt=3, _AMQ_ROUTING_TYPE=1}");
        browseReturns(values);
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);

        MessageSummary summary = service().page("orders", null, 1, 50).messages().get(0);

        assertEquals(Map.of("orderRef", "A-17", "attempt", "3"), summary.properties());
    }

    @Test
    @DisplayName("a message with no properties has an empty map, not a null")
    void handlesMessagesWithoutProperties()
    {
        browseReturns(message("ID:1", "body"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);

        assertTrue(service().page("orders", null, 1, 50).messages().get(0).properties().isEmpty());
    }

    @Test
    @DisplayName("a large message is flagged from the browse attribute")
    void flagsLargeMessages()
    {
        Map<String, Object> large = new LinkedHashMap<>(message("ID:1", "preview"));
        large.put("largeMessage", Boolean.TRUE);
        browseReturns(large, message("ID:2", "ordinary"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(2L);

        List<MessageSummary> messages = service().page("orders", null, 1, 50).messages();

        assertTrue(messages.get(0).largeMessage());
        assertFalse(messages.get(1).largeMessage());
    }

    @Test
    @DisplayName("the export body budget cuts a body rather than holding all of it")
    void exportCutsABodyThatExceedsTheBudget() throws Exception
    {
        browseReturns(message("ID:1", "q".repeat(256) + ", + 744 more"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(1L);
        browserHolds(text("ID:1", "q".repeat(1000)));

        QueueBrowseService service = new QueueBrowseService(brokerSession, 200, 200000, 20000, 400L);
        MessageSummary summary = service.pageForExport("orders", "orders", null, 1, 5000).messages().get(0);

        assertEquals(400, summary.bodyPreview().length());
        assertTrue(summary.bodyTruncated());
    }

    @Test
    @DisplayName("once the budget is spent the pass stops reading, and later rows say so")
    void exportStopsReadingWhenTheBudgetIsSpent() throws Exception
    {
        browseReturns(message("ID:1", "a".repeat(256) + ", + 744 more"),
                message("ID:2", "b".repeat(256) + ", + 744 more"));
        given(management.invoke(QUEUE, "countMessages", "")).willReturn(2L);
        TextMessage first = text("ID:1", "a".repeat(300));
        TextMessage second = text("ID:2", "b".repeat(300));
        browserHolds(first, second);

        QueueBrowseService service = new QueueBrowseService(brokerSession, 200, 200000, 20000, 300L);
        List<MessageSummary> messages = service.pageForExport("orders", "orders", null, 1, 5000).messages();

        assertEquals(300, messages.get(0).bodyPreview().length());
        assertEquals("b".repeat(256), messages.get(1).bodyPreview());
        assertTrue(messages.get(1).bodyTruncated());
        verify(second, never()).getText();
    }

    private QueueBrowseService service()
    {
        return service(200, 200000);
    }

    private QueueBrowseService service(int previewChars, int detailChars)
    {
        return new QueueBrowseService(brokerSession, previewChars, detailChars, 20000, 20_000_000L);
    }

    private TextMessage text(String messageId, String body) throws Exception
    {
        TextMessage message = mock(TextMessage.class);
        given(message.getJMSMessageID()).willReturn(messageId);
        given(message.getText()).willReturn(body);
        return message;
    }

    private void browserHolds(Message... messages) throws Exception
    {
        given(jmsBrowser.getEnumeration()).willReturn(Collections.enumeration(List.of(messages)));
    }

    @SafeVarargs
    private void browseReturns(Map<String, Object>... messages)
    {
        given(management.invoke(QUEUE, "browse", 1, 50)).willReturn(browseReply(messages));
        given(management.invoke(QUEUE, "browse", 1, 5000)).willReturn(browseReply(messages));
    }

    @SafeVarargs
    private Object browseReply(Map<String, Object>... messages)
    {
        List<CompositeData> entries = new ArrayList<>();
        for (Map<String, Object> message : messages)
        {
            entries.add(composite(message));
        }
        // browse() answers a Map whose single key is the literal class name.
        return Map.of("javax.management.openmbean.CompositeData", entries.toArray(new CompositeData[0]));
    }

    /** A text message as the broker reports it: messageID a long, type a code, userID the JMS ID. */
    private Map<String, Object> message(String messageId, String body)
    {
        Map<String, Object> values = new LinkedHashMap<>(nonTextMessage(messageId));
        values.put("type", 3);
        values.put("text", body);
        return values;
    }

    private Map<String, Object> nonTextMessage(String messageId)
    {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("messageID", 4200L);
        values.put("userID", messageId);
        values.put("type", 4);
        values.put("timestamp", 1758326400000L);
        values.put("priority", 4);
        values.put("durable", Boolean.TRUE);
        values.put("redelivered", Boolean.FALSE);
        values.put("persistentSize", 128L);
        values.put("protocol", "CORE");
        values.put("largeMessage", Boolean.FALSE);
        return values;
    }

    /** A property table as browse reports one: rows of key/value, typed per Java type. */
    private TabularData propertyTable(String type, Map<String, String> entries)
    {
        try
        {
            CompositeType rowType = new CompositeType("java.util.Map<java.lang.String, java.lang." + type + ">", "row",
                    new String[]
                    { "key", "value"
                    }, new String[]
                    { "key", "value"
                    }, new OpenType<?>[]
                    { SimpleType.STRING, SimpleType.STRING
                    });
            TabularDataSupport table = new TabularDataSupport(
                    new TabularType("properties", "properties", rowType, new String[]
                    { "key"
                    }));
            for (Map.Entry<String, String> entry : entries.entrySet())
            {
                table.put(new CompositeDataSupport(rowType, new String[]
                { "key", "value"
                }, new Object[]
                { entry.getKey(), entry.getValue()
                }));
            }
            return table;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private CompositeData composite(Map<String, Object> values)
    {
        try
        {
            String[] names = values.keySet().toArray(new String[0]);
            OpenType<?>[] types = new OpenType<?>[names.length];
            for (int i = 0; i < names.length; i++)
            {
                types[i] = openType(values.get(names[i]));
            }
            return new CompositeDataSupport(new CompositeType("message", "message", names, names, types), names,
                    values.values().toArray());
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private OpenType<?> openType(Object value)
    {
        if (value instanceof TabularData tabular)
        {
            return tabular.getTabularType();
        }
        if (value instanceof Long)
        {
            return SimpleType.LONG;
        }
        if (value instanceof Integer)
        {
            return SimpleType.INTEGER;
        }
        if (value instanceof Boolean)
        {
            return SimpleType.BOOLEAN;
        }
        return SimpleType.STRING;
    }
}
