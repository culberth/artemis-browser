package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where an address's messages can go besides its subscriptions: its settings and its diverts.
 *
 * <p>
 * The settings JSON is what 2.44.0 returned for an address matched only by {@code #} — note what is missing from it as
 * much as what is there. The divert reads mirror the verified shape: {@code getDivertNames} an {@code Object[]},
 * {@code exclusive} a {@code Boolean}, everything else a String, an unset transformer null.
 */
class AddressRoutingTest
{

    private static final String EVENTS_SETTINGS = """
        {"addressFullMessagePolicy":"PAGE","maxSizeBytes":-1,"maxReadPageBytes":20971520,"maxReadPageMessages":-1,
         "pageLimitBytes":-1,"pageLimitMessages":-1,"maxSizeMessages":-1,"pageSizeBytes":10485760,
         "messageCounterHistoryDayLimit":10,"redeliveryDelay":0,"deadLetterAddress":"DLQ",
         "expiryAddress":"ExpiryQueue","slowConsumerThresholdMeasurementUnit":"MESSAGES_PER_SECOND",
         "autoCreateQueues":true,"autoDeleteQueues":false,"autoCreateAddresses":true,"autoDeleteAddresses":false,
         "managementBrowsePageSize":200}""";

    private BrokerSession brokerSession;
    private ManagementChannel management;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        given(brokerSession.requireManagement()).willReturn(management);
    }

    @Test
    @DisplayName("settings arrive with bare numbers and JSON booleans, and are read as text")
    void readsTheSettings()
    {
        given(management.invoke(ResourceNames.BROKER, "getAddressSettingsAsJSON", "events"))
                .willReturn(EVENTS_SETTINGS);

        AddressSettings settings = new AddressDirectory(brokerSession, null).settings("events");

        assertEquals("DLQ", settings.deadLetterAddress());
        assertEquals("ExpiryQueue", settings.expiryAddress());
        assertEquals("PAGE", settings.fullPolicy());
        assertEquals("unlimited", settings.maxSize(), "-1 is the broker's 'no limit'");
        assertTrue(settings.flag("autoCreateQueues"));
        assertFalse(settings.flag("autoDeleteQueues"));
        assertEquals("200", settings.get("managementBrowsePageSize"));
    }

    @Test
    @DisplayName("a setting the broker left out reads as not set, never as zero")
    void absentKeysAreNotZero()
    {
        given(management.invoke(ResourceNames.BROKER, "getAddressSettingsAsJSON", "events"))
                .willReturn(EVENTS_SETTINGS);

        AddressSettings settings = new AddressDirectory(brokerSession, null).settings("events");

        // Both absent from a real broker's reply at their defaults. Zero delivery attempts would be
        // a broker that dead-letters everything on sight.
        assertNull(settings.maxDeliveryAttempts());
        assertNull(settings.retroactiveMessageCount());
    }

    @Test
    @DisplayName("diverts are read by name, one attribute at a time, with exclusive as a Boolean")
    void readsDiverts()
    {
        given(management.invoke(ResourceNames.BROKER, "getDivertNames")).willReturn(new Object[]
        { "events-audit", "alerts-archive"
        });
        divert("alerts-archive", "alerts", "alerts.archive", "severity = 'high'", Boolean.TRUE, "PASS");
        divert("events-audit", "events", "audit", null, Boolean.FALSE, "ANYCAST");

        List<Divert> diverts = new DivertDirectory(brokerSession).all();

        assertEquals(List.of("alerts-archive", "events-audit"), diverts.stream().map(Divert::name).toList());
        Divert archive = diverts.get(0);
        assertTrue(archive.exclusive());
        assertTrue(archive.filtered());
        assertFalse(archive.transformed(), "an unset transformer comes back null");
        assertFalse(diverts.get(1).filtered(), "a null filter takes everything");
        assertEquals(List.of(archive), DivertDirectory.from(diverts, "alerts"));
        assertEquals(List.of(diverts.get(1)), DivertDirectory.to(diverts, "audit"));
    }

    @Test
    @DisplayName("a divert destroyed between the listing and its reads is left out, not an error")
    void skipsAVanishedDivert()
    {
        given(management.invoke(ResourceNames.BROKER, "getDivertNames")).willReturn(new Object[]
        { "gone"
        });
        given(management.attribute(ResourceNames.DIVERT + "gone", "address"))
                .willThrow(new BrokerException("AMQ229067: Cannot find resource with name divert.gone"));

        assertTrue(new DivertDirectory(brokerSession).all().isEmpty());
    }

    @Test
    @DisplayName("a named dead-letter address is checked against what exists, since one that does not drops messages")
    void knowsWhetherANamedAddressExists()
    {
        AddressRouting routing = new AddressRouting(null, null, List.of(), List.of(), Set.of("DLQ"));

        assertTrue(routing.exists("DLQ"));
        assertFalse(routing.exists("ExpiryQueue"));
        assertFalse(routing.exists(null));
    }

    private void divert(String name, String address, String to, String filter, Boolean exclusive, String routing)
    {
        String resource = ResourceNames.DIVERT + name;
        given(management.attribute(resource, "address")).willReturn(address);
        given(management.attribute(resource, "forwardingAddress")).willReturn(to);
        given(management.attribute(resource, "filter")).willReturn(filter);
        given(management.attribute(resource, "exclusive")).willReturn(exclusive);
        given(management.attribute(resource, "routingType")).willReturn(routing);
        given(management.attribute(resource, "transformerClassName")).willReturn(null);
    }
}
