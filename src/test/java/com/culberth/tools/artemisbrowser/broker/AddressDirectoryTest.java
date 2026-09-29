package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code listAddresses} quotes every value like {@code listQueues} does, and then encodes {@code routingTypes} as a
 * JSON array <em>inside</em> one of those strings. Rendered raw it puts escaped brackets on the page, and compared raw
 * it stops a multicast address being recognised as one.
 */
class AddressDirectoryTest
{

    private BrokerSession brokerSession;
    private ManagementChannel management;
    private QueueDirectory queueDirectory;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        queueDirectory = mock(QueueDirectory.class);
        given(brokerSession.requireManagement()).willReturn(management);
        given(queueDirectory.overview()).willReturn(List.of());
    }

    @Test
    @DisplayName("routingTypes is a JSON array inside a JSON string and is unwrapped")
    void unwrapsRoutingTypes()
    {
        listAddressesReturns(1, address("orders", "[\\\"ANYCAST\\\"]"));

        assertEquals("ANYCAST", directory().overview().get(0).routingTypes());
    }

    @Test
    @DisplayName("an address carrying both routing types reads as multicast")
    void recognisesMulticast()
    {
        listAddressesReturns(1, address("events", "[\\\"ANYCAST\\\",\\\"MULTICAST\\\"]"));

        AddressOverview address = directory().overview().get(0);

        assertEquals("ANYCAST, MULTICAST", address.routingTypes());
        assertTrue(address.multicast());
    }

    @Test
    @DisplayName("a routingTypes value that will not parse is cleaned up rather than shown raw")
    void cleansUpAnUnparseableRoutingTypes()
    {
        listAddressesReturns(1, address("odd", "[\\\"ANYCAST\\\""));

        assertEquals("ANYCAST", directory().overview().get(0).routingTypes());
    }

    @Test
    @DisplayName("a multicast address shows the queue each subscriber reads from")
    void groupsQueuesUnderTheirAddress()
    {
        given(queueDirectory.overview())
                .willReturn(List.of(queue("sub-a", "events"), queue("sub-b", "events"), queue("orders", "orders")));
        listAddressesReturns(1, address("events", "[\\\"MULTICAST\\\"]"), address("orders", "[\\\"ANYCAST\\\"]"));

        List<AddressOverview> addresses = directory().overview();

        assertEquals(2, addresses.get(0).queues().size());
        assertEquals(List.of("sub-a", "sub-b"), addresses.get(0).queues().stream().map(QueueOverview::name).toList());
        assertEquals(1, addresses.get(1).queues().size());
    }

    @Test
    @DisplayName("the temporary address behind our own reply queue is left out")
    void hidesOurOwnTemporaryAddress()
    {
        listAddressesReturns(1, address("orders", "[\\\"ANYCAST\\\"]"),
                "{\"name\":\"tmp-reply-9f2c\",\"routingTypes\":\"[\\\"ANYCAST\\\"]\",\"temporary\":\"true\"}");

        List<AddressOverview> addresses = directory().overview();

        assertEquals(1, addresses.size());
        assertEquals("orders", addresses.get(0).name());
    }

    @Test
    @DisplayName("a temporary address someone is actually using is still shown")
    void keepsATemporaryAddressThatHasQueues()
    {
        given(queueDirectory.overview()).willReturn(List.of(queue("in-use", "tmp-theirs")));
        listAddressesReturns(1,
                "{\"name\":\"tmp-theirs\",\"routingTypes\":\"[\\\"ANYCAST\\\"]\",\"temporary\":\"true\"}");

        assertEquals(1, directory().overview().size());
    }

    @Test
    @DisplayName("counters arrive quoted, and unrouted messages are called out")
    void readsQuotedCounters()
    {
        listAddressesReturns(1,
                "{\"name\":\"orders\",\"routingTypes\":\"[\\\"ANYCAST\\\"]\","
                        + "\"messageCount\":\"12\",\"addressSize\":\"4096\",\"routedMessageCount\":\"12\","
                        + "\"unroutedMessageCount\":\"3\",\"paging\":\"false\",\"internal\":\"false\"}");

        AddressOverview address = directory().overview().get(0);

        assertEquals(12, address.messageCount());
        assertEquals(4096L, address.addressSize().value());
        assertEquals(3, address.unroutedMessageCount());
        assertTrue(address.hasUnrouted());
        assertFalse(address.paging());
    }

    @Test
    @DisplayName("the storage fields arrive quoted, as recorded from probe-fail on 2.55.0 and 2.57.0")
    void readsStorageFields()
    {
        listAddressesReturns(1, "{\"id\":\"219\",\"name\":\"probe-fail\",\"routingTypes\":\"[\\\"ANYCAST\\\"]\","
                + "\"queueCount\":\"1\",\"internal\":\"false\",\"temporary\":\"false\",\"autoCreated\":\"true\","
                + "\"paused\":\"false\",\"unroutedMessageCount\":\"0\",\"routedMessageCount\":\"8\","
                + "\"messageCount\":\"7\",\"addressLimitPercent\":\"108\",\"numberOfPages\":\"0\","
                + "\"addressSize\":\"21679\",\"numberOfBytesPerPage\":\"10000\",\"paging\":\"true\"}");

        AddressOverview address = directory().overview().get(0);

        assertEquals(108L, address.limitPercent().value());
        assertEquals(0L, address.pages().value());
        assertTrue(address.fullWithoutPaging(), "paging with no pages is a full address under FAIL or DROP");
        assertFalse(address.pagedToDisk());
        assertTrue(address.underPressure());
    }

    @Test
    @DisplayName("a listing without the storage fields leaves them unsupported, never zero")
    void missingStorageFieldsAreNotZero()
    {
        listAddressesReturns(1, "{\"name\":\"old\",\"routingTypes\":\"[]\",\"addressLimitPercent\":\"lots\"}");

        AddressOverview address = directory().overview().get(0);

        assertEquals(Availability.UNSUPPORTED, address.addressSize().availability());
        assertEquals(Availability.UNSUPPORTED, address.pages().availability());
        assertEquals(Availability.FAILED, address.limitPercent().availability());
        assertNull(address.sizeText());
        assertFalse(address.underPressure());
    }

    @Test
    @DisplayName("the management block is read per address, and a refusal is a reading, not false")
    void readsTheManagementBlock()
    {
        given(management.attribute(ResourceNames.ADDRESS + "held", "blockedViaManagement")).willReturn(true);
        given(management.attribute(ResourceNames.ADDRESS + "open", "blockedViaManagement")).willReturn("false");
        given(management.attribute(ResourceNames.ADDRESS + "gone", "blockedViaManagement"))
                .willThrow(new ManagementRefusal(Availability.UNAVAILABLE, "Problem while retrieving attribute"));

        assertTrue(directory().blockedViaManagement("held").value());
        assertFalse(directory().blockedViaManagement("open").value());
        assertEquals(Availability.UNAVAILABLE, directory().blockedViaManagement("gone").availability());
    }

    @Test
    @DisplayName("find answers null for an address the broker does not have")
    void findMissesCleanly()
    {
        listAddressesReturns(1, address("orders", "[\\\"ANYCAST\\\"]"));

        assertEquals("orders", directory().find("orders").name());
        assertNull(directory().find("nope"));
    }

    @Test
    @DisplayName("a reply that is not the expected JSON is reported, not swallowed")
    void unreadableReplyFails()
    {
        given(management.invoke(ResourceNames.BROKER, "listAddresses", "", 1, 200)).willReturn("not json at all");

        assertThrows(BrokerException.class, () -> directory().overview());
    }

    private AddressDirectory directory()
    {
        return new AddressDirectory(brokerSession, queueDirectory);
    }

    private void listAddressesReturns(int page, String... addresses)
    {
        given(management.invoke(ResourceNames.BROKER, "listAddresses", "", page, 200))
                .willReturn("{\"data\":[" + String.join(",", addresses) + "],\"count\":" + addresses.length + "}");
    }

    private String address(String name, String escapedRoutingTypes)
    {
        return "{\"name\":\"" + name + "\",\"routingTypes\":\"" + escapedRoutingTypes + "\",\"messageCount\":\"0\","
                + "\"addressSize\":\"0\",\"routedMessageCount\":\"0\",\"unroutedMessageCount\":\"0\","
                + "\"paging\":\"false\",\"internal\":\"false\",\"temporary\":\"false\"}";
    }

    private QueueOverview queue(String name, String address)
    {
        return new QueueOverview(name, address, "MULTICAST", 0, 0, 0, 0, 0, 0, true, false, false);
    }
}
