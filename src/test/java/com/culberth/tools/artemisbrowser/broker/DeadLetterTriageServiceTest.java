package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.culberth.tools.artemisbrowser.broker.DeadLetterTriage.Origin;
import com.culberth.tools.artemisbrowser.broker.DeadLetterTriage.ValueGroup;
import com.culberth.tools.artemisbrowser.broker.DeadLetterTriageService.Grouping;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Grouping a sample by origin without inventing anything: unknown origin stays unknown, a missing property is "not
 * set", only a recorded expiry counts as expired, and the sample is never passed off as the queue.
 */
class DeadLetterTriageServiceTest
{

    private static final String DLQ = "DLQ";

    private BrokerSession brokerSession;
    private QueueDirectory queueDirectory;
    private QueueBrowseService browseService;
    private AddressDirectory addressDirectory;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        queueDirectory = mock(QueueDirectory.class);
        browseService = mock(QueueBrowseService.class);
        addressDirectory = mock(AddressDirectory.class);
        given(queueDirectory.overview()).willReturn(List.of(queue(DLQ, 3), queue("orders", 0)));
        given(queueDirectory.stats(DLQ)).willReturn(stats(DLQ, 3));
        given(addressDirectory.settings(anyString()))
                .willReturn(AddressSettings.of(Map.of("deadLetterAddress", DLQ, "expiryAddress", "ExpiryQueue")));
    }

    // ------------------------------------------------------------------ grouping, no broker

    @Test
    @DisplayName("messages are grouped by origin address and queue, largest first, with shares of the sample")
    void groupsByOrigin()
    {
        Grouping grouping = DeadLetterTriageService.group(List.of(core(1, "orders", "orders", "1"),
                core(2, "orders", "orders", "1"), core(3, "events", "client.sub", "0")), null, 50);

        assertEquals(2, grouping.origins().size());
        Origin first = grouping.origins().getFirst();
        assertEquals("orders", first.address());
        assertEquals("orders", first.queue());
        assertEquals(2, first.count());
        assertEquals(List.of("anycast"), first.routingTypes());
        assertEquals(List.of("ID:1", "ID:2"), first.examples());
        assertEquals(List.of("multicast"), grouping.origins().get(1).routingTypes());
        assertEquals("client.sub", grouping.origins().get(1).queue());
        assertEquals(0, grouping.noOrigin());
    }

    @Test
    @DisplayName("an AMQP message's origin is read from its prefixed names, and joins the same group as a core one")
    void readsAmqpOrigin()
    {
        MessageSummary amqp = row(2, "AMQP",
                props("extraProperties._AMQ_ORIG_ADDRESS", "orders", "extraProperties._AMQ_ORIG_QUEUE", "orders",
                        "messageAnnotations.x-opt-ORIG-ADDRESS", "orders", "messageAnnotations.x-opt-ORIG-ROUTING-TYPE",
                        "1"));
        Grouping grouping = DeadLetterTriageService.group(List.of(core(1, "orders", "orders", "1"), amqp), null, 50);

        assertEquals(1, grouping.origins().size());
        assertEquals(2, grouping.origins().getFirst().count());
        assertEquals(List.of("CORE", "AMQP"), grouping.origins().getFirst().protocols());
    }

    @Test
    @DisplayName("a message with no origin is counted as unknown, and listed even past the group limit")
    void keepsUnknownOriginUnknown()
    {
        List<MessageSummary> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++)
        {
            rows.add(core(i, "a" + i, "a" + i, "1"));
            rows.add(core(100 + i, "a" + i, "a" + i, "1"));
        }
        rows.add(row(999, "CORE", props("app", "x")));

        Grouping grouping = DeadLetterTriageService.group(rows, null, 2);

        assertEquals(1, grouping.noOrigin());
        assertEquals(3, grouping.origins().size(), "two largest plus the unknown one");
        Origin unknown = grouping.origins().getLast();
        assertFalse(unknown.known());
        assertEquals("no origin recorded", unknown.label());
        assertEquals(3, grouping.originsNotListed());
        assertEquals(6, grouping.messagesNotListed());
    }

    @Test
    @DisplayName("only a recorded _AMQ_ACTUAL_EXPIRY counts as expired; a zero expiration header does not")
    void countsOnlyRecordedExpiry()
    {
        Map<String, String> expired = props("_AMQ_ORIG_ADDRESS", "stale", "_AMQ_ORIG_QUEUE", "stale",
                "_AMQ_ACTUAL_EXPIRY", "1790791973115");
        Map<String, String> notExpired = props("_AMQ_ORIG_ADDRESS", "stale", "_AMQ_ORIG_QUEUE", "stale",
                "_AMQ_ACTUAL_EXPIRY", "not a number");

        Grouping grouping = DeadLetterTriageService.group(List.of(row(1, "CORE", expired), row(2, "CORE", notExpired)),
                null, 50);

        Origin stale = grouping.origins().getFirst();
        assertEquals(1, stale.expired());
        assertEquals(1790791973115L, stale.firstExpired());
        assertEquals(1, grouping.expired());
    }

    @Test
    @DisplayName("an origin address without a queue is its own group, and counted as partial")
    void countsPartialOrigin()
    {
        Grouping grouping = DeadLetterTriageService.group(List.of(row(1, "CORE", props("_AMQ_ORIG_ADDRESS", "a"))),
                null, 50);

        assertEquals(1, grouping.partialOrigin());
        assertEquals("a / queue not recorded", grouping.origins().getFirst().label());
    }

    @Test
    @DisplayName("grouping by a property counts a missing one as not set, never as an empty value")
    void groupsByPropertyValue()
    {
        MessageSummary empty = row(4, "CORE", props("_AMQ_ORIG_ADDRESS", "b", "_AMQ_ORIG_QUEUE", "b", "code", ""));
        Grouping grouping = DeadLetterTriageService.group(List.of(withCode(core(1, "a", "a", "1"), "E1"),
                withCode(core(2, "b", "b", "1"), "E1"), core(3, "a", "a", "1"), empty), "code", 50);

        var byCode = grouping.groupBy();
        assertEquals("code", byCode.property());
        assertEquals(1, byCode.notSet());
        assertEquals(2, byCode.values().size());
        ValueGroup e1 = byCode.values().getFirst();
        assertEquals("E1", e1.value());
        assertEquals(2, e1.count());
        assertEquals(List.of("a / a", "b / b"), e1.origins());
        assertEquals("", byCode.values().get(1).value(), "an empty value is a value");
    }

    @Test
    @DisplayName("a long value is grouped by its first characters, and marked cut")
    void cutsLongValues()
    {
        String longValue = "x".repeat(DeadLetterTriageService.MAX_VALUE_CHARS + 50);
        Grouping grouping = DeadLetterTriageService.group(
                List.of(withCode(core(1, "a", "a", "1"), longValue), withCode(core(2, "a", "a", "1"), longValue + "y")),
                "code", 50);

        ValueGroup only = grouping.groupBy().values().getFirst();
        assertEquals(DeadLetterTriageService.MAX_VALUE_CHARS, only.value().length());
        assertTrue(only.truncated());
        assertEquals(2, only.count());
    }

    @Test
    @DisplayName("property names are counted, and the broker's own marked as such")
    void countsPropertyNames()
    {
        Grouping grouping = DeadLetterTriageService
                .group(List.of(withCode(core(1, "a", "a", "1"), "E1"), core(2, "a", "a", "1")), null, 50);

        var code = grouping.properties().stream().filter(p -> p.name().equals("code")).findFirst().orElseThrow();
        assertEquals(1, code.count());
        assertFalse(code.artemis());
        var origin = grouping.properties().stream().filter(p -> p.name().equals("_AMQ_ORIG_ADDRESS")).findFirst()
                .orElseThrow();
        assertEquals(2, origin.count());
        assertTrue(origin.artemis());
        assertTrue(DeadLetterTriageService.bookkeeping("messageAnnotations.x-opt-ORIG-QUEUE"));
        assertFalse(DeadLetterTriageService.bookkeeping("applicationProperties.count"));
    }

    // ------------------------------------------------------------------ collection

    @Test
    @DisplayName("the sample is read page by page and stops at the sample size")
    void stopsAtTheSampleSize()
    {
        int page = DeadLetterTriageService.PAGE_SIZE;
        given(browseService.rows(eq(DLQ), eq(""), anyInt(), eq(page)))
                .willAnswer(call -> fullPage((int) call.getArgument(2)));
        given(queueDirectory.overview()).willReturn(List.of(queue(DLQ, 10_000)));
        given(queueDirectory.stats(DLQ)).willReturn(stats(DLQ, 10_000));

        DeadLetterTriage triage = service(2000).triage(DLQ, null, page + 10, null);

        assertEquals(page + 10, triage.sampled());
        assertFalse(triage.reachedEnd());
        assertFalse(triage.wholeQueue());
        verify(browseService, times(2)).rows(eq(DLQ), eq(""), anyInt(), eq(page));
    }

    @Test
    @DisplayName("a sample smaller than a page reads a page of that size, and a full one is not the end of the queue")
    void readsASmallSampleInOnePage()
    {
        given(browseService.rows(DLQ, "", 1, 2))
                .willReturn(List.of(core(1, "orders", "orders", "1"), core(2, "orders", "orders", "1")));

        DeadLetterTriage triage = service(2000).triage(DLQ, null, 2, null);

        assertEquals(2, triage.sampled());
        assertEquals(2, triage.pageSize());
        assertFalse(triage.reachedEnd());
        assertFalse(triage.wholeQueue());
    }

    @Test
    @DisplayName("a sample larger than the limit is cut to the limit; zero means the default")
    void capsTheSample()
    {
        given(browseService.rows(anyString(), anyString(), anyInt(), anyInt())).willReturn(List.of());

        assertEquals(100, service(100).triage(DLQ, null, 1_000_000, null).requested());
        assertEquals(500, service(2000).triage(DLQ, null, 0, null).requested());
    }

    @Test
    @DisplayName("a short browse is the end of the queue, and with an unchanged count, the whole of it")
    void recognisesTheWholeQueue()
    {
        given(browseService.rows(DLQ, "", 1, DeadLetterTriageService.PAGE_SIZE)).willReturn(
                List.of(core(1, "orders", "orders", "1"), core(2, "orders", "orders", "1"), row(3, "CORE", props())));

        DeadLetterTriage triage = service(2000).triage(DLQ, null, 0, null);

        assertTrue(triage.reachedEnd());
        assertTrue(triage.wholeQueue());
        assertFalse(triage.moved());
        assertEquals("66.7", triage.share(2));
        assertEquals(1, triage.noOrigin());
    }

    @Test
    @DisplayName("a row read on two pages is counted once, and the sample says the queue moved")
    void dropsDuplicates()
    {
        int page = DeadLetterTriageService.PAGE_SIZE;
        List<MessageSummary> first = fullPage(1);
        List<MessageSummary> second = new ArrayList<>(List.of(first.getLast()));
        second.add(core(99_999, "orders", "orders", "1"));
        given(browseService.rows(DLQ, "", 1, page)).willReturn(first);
        given(browseService.rows(DLQ, "", 2, page)).willReturn(second);

        DeadLetterTriage triage = service(2000).triage(DLQ, null, 1000, null);

        assertEquals(page + 1, triage.sampled());
        assertEquals(1, triage.duplicates());
        assertTrue(triage.moved());
    }

    @Test
    @DisplayName("an origin's settings are compared with this queue's address, and read once per address")
    void readsOriginSettings()
    {
        given(browseService.rows(DLQ, "", 1, DeadLetterTriageService.PAGE_SIZE)).willReturn(List.of(
                core(1, "orders", "orders", "1"), core(2, "orders", "orders", "1"), core(3, "events", "c.sub", "0")));
        given(addressDirectory.settings("events"))
                .willReturn(AddressSettings.of(Map.of("deadLetterAddress", "OtherDLQ", "maxDeliveryAttempts", "3")));

        DeadLetterTriage triage = service(2000).triage(DLQ, null, 0, null);

        Origin orders = triage.origins().getFirst();
        assertTrue(orders.deadLettersHere());
        assertFalse(orders.expiresHere());
        assertTrue(orders.queueExists());
        Origin events = triage.origins().get(1);
        assertFalse(events.deadLettersHere());
        assertEquals("dead-letter address OtherDLQ, expiry address none, 3 delivery attempts", events.settingsText());
        assertFalse(events.queueExists(), "c.sub is not on the broker");
        verify(addressDirectory, times(1)).settings("orders");
    }

    @Test
    @DisplayName("past the settings limit an origin's settings are not collected, and said so")
    void limitsSettingsReads()
    {
        given(browseService.rows(DLQ, "", 1, DeadLetterTriageService.PAGE_SIZE))
                .willReturn(List.of(core(1, "a", "a", "1"), core(2, "a", "a", "1"), core(3, "b", "b", "1")));

        DeadLetterTriage triage = new DeadLetterTriageService(brokerSession, queueDirectory, browseService,
                addressDirectory, 500, 2000, 50, 1).triage(DLQ, null, 0, null);

        assertTrue(triage.origins().getFirst().settingsRead());
        Origin b = triage.origins().get(1);
        assertEquals(Availability.NOT_COLLECTED, b.settings().availability());
        assertEquals(1, triage.settingsNotRead());
        verify(addressDirectory, never()).settings("b");
    }

    @Test
    @DisplayName("refused settings are shown as refused, and the grouping still stands")
    void survivesRefusedSettings()
    {
        given(browseService.rows(DLQ, "", 1, DeadLetterTriageService.PAGE_SIZE))
                .willReturn(List.of(core(1, "orders", "orders", "1")));
        given(addressDirectory.settings(anyString()))
                .willThrow(new ManagementRefusal(Availability.DENIED, "AMQ229032"));

        DeadLetterTriage triage = service(2000).triage(DLQ, null, 0, null);

        Origin orders = triage.origins().getFirst();
        assertEquals(Availability.DENIED, orders.settings().availability());
        assertFalse(orders.deadLettersHere());
        assertEquals(1, orders.count());
    }

    @Test
    @DisplayName("a queue the broker does not list is not browsed")
    void refusesAnUnlistedQueue()
    {
        assertNull(service(2000).triage("nowhere", null, 0, null));
        verify(browseService, never()).rows(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("an invalid filter reaches the caller rather than reading as an empty sample")
    void passesOnAnInvalidFilter()
    {
        given(browseService.rows(DLQ, "==", 1, DeadLetterTriageService.PAGE_SIZE))
                .willThrow(new InvalidFilterException("AMQ229020: Invalid filter: =="));

        assertThrows(InvalidFilterException.class, () -> service(2000).triage(DLQ, "==", 0, null));
    }

    // ------------------------------------------------------------------ fixtures

    private DeadLetterTriageService service(int maxSample)
    {
        return new DeadLetterTriageService(brokerSession, queueDirectory, browseService, addressDirectory, 500,
                maxSample, 50, 20);
    }

    private static List<MessageSummary> fullPage(int page)
    {
        List<MessageSummary> rows = new ArrayList<>();
        for (int i = 0; i < DeadLetterTriageService.PAGE_SIZE; i++)
        {
            rows.add(core((page - 1) * DeadLetterTriageService.PAGE_SIZE + i, "orders", "orders", "1"));
        }
        return rows;
    }

    private static MessageSummary core(long id, String address, String queue, String routing)
    {
        return row(id, "CORE", props("_AMQ_ORIG_ADDRESS", address, "_AMQ_ORIG_QUEUE", queue, "_AMQ_ORIG_ROUTING_TYPE",
                routing, "_AMQ_ORIG_MESSAGE_ID", Long.toString(id + 1000)));
    }

    private static MessageSummary withCode(MessageSummary row, String code)
    {
        Map<String, String> properties = new LinkedHashMap<>(row.properties());
        properties.put("code", code);
        return new MessageSummary(row.position(), row.messageId(), row.coreId(), row.type(), row.timestamp(),
                row.timestampText(), row.priority(), row.persistent(), row.redelivered(), row.sizeBytes(),
                row.protocol(), row.largeMessage(), properties, row.bodyPreview(), row.bodyTruncated());
    }

    private static MessageSummary row(long id, String protocol, Map<String, String> properties)
    {
        return new MessageSummary(id, "ID:" + id, Long.toString(id), "Text", 1_790_000_000_000L + id, "", 4, true,
                false, 100, protocol, false, properties, "body", false);
    }

    private static Map<String, String> props(String... pairs)
    {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static QueueOverview queue(String name, long messages)
    {
        return new QueueOverview(name, name, "ANYCAST", messages, 0, 0, 0, messages, 0, true, false, false);
    }

    private static QueueStats stats(String name, long messages)
    {
        return new QueueStats(name, name, "ANYCAST", messages, 0, 0, 0, messages, 0, true, false);
    }
}
