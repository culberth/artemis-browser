package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotServiceTest
{

    private BrokerSession session;
    private BrokerInfoService info;
    private QueueDirectory queues;
    private AddressDirectory addresses;
    private RateService rates;
    private StuckDiagnosisService diagnosis;
    private ConnectivityService connectivity;

    @BeforeEach
    void mocks()
    {
        session = mock(BrokerSession.class);
        info = mock(BrokerInfoService.class);
        queues = mock(QueueDirectory.class);
        addresses = mock(AddressDirectory.class);
        rates = mock(RateService.class);
        diagnosis = mock(StuckDiagnosisService.class);
        connectivity = mock(ConnectivityService.class);
        given(connectivity.collect(org.mockito.ArgumentMatchers.any())).willReturn(ConnectivityFixtures.standalone());
        given(session.info()).willReturn(new ConnectionInfo("localhost", 61616, "artemis"));
        given(info.health()).willReturn(BrokerHealth.of("2.55.0", "1h", "STARTED", "n", 1, 1, 1, 0, 0, 10, 90));
        given(info.acceptors()).willReturn(List.of());
        given(info.connections()).willReturn(List.of());
        given(info.consumers()).willReturn(List.of());
        given(info.producers()).willReturn(List.of());
        given(queues.overview()).willReturn(List.of());
        given(addresses.overview()).willReturn(List.of());
        given(addresses.settings(anyString())).willReturn(AddressSettings.of(Map.of()));
        given(rates.trends()).willReturn(Trends.none());
        given(diagnosis.run(anyBoolean(), org.mockito.ArgumentMatchers.any()))
                .willReturn(new Diagnosis(List.of(), 0, 0));
    }

    @Test
    @DisplayName("connectivity is read once, from the snapshot's own queue listing, and handed to diagnose")
    void connectivityIsReadOnce()
    {
        Connectivity troubled = ConnectivityFixtures.troubled();
        given(connectivity.collect(org.mockito.ArgumentMatchers.any())).willReturn(troubled);

        IncidentSnapshot snapshot = service(1000, 200).collect();

        assertEquals(troubled, snapshot.connectivity().value());
        verify(connectivity, org.mockito.Mockito.times(1)).collect(org.mockito.ArgumentMatchers.any());
        verify(diagnosis).run(false, troubled);
    }

    @Test
    @DisplayName("a listing past the row limit is cut, and says how many there were")
    void boundsListings()
    {
        given(info.connections()).willReturn(List.of(new BrokerConnection("a", "", "", 1, false),
                new BrokerConnection("b", "", "", 1, false), new BrokerConnection("c", "", "", 1, false)));

        IncidentSnapshot snapshot = service(2, 200).collect();

        assertEquals(2, snapshot.connections().rows().value().size());
        assertEquals(3, snapshot.connections().total());
        assertTrue(snapshot.connections().truncated());
    }

    @Test
    @DisplayName("settings go first to addresses with something to explain, and the rest are counted as omitted")
    void choosesAddressesWorthExplaining()
    {
        AddressOverview quiet = address("a-quiet", false, 0);
        AddressOverview full = address("z-full", true, 0).withStorage(108, 0);
        AddressOverview internal = new AddressOverview("activemq.notifications", "MULTICAST", 0, 0, 0, 5, false, true,
                false, List.of());
        given(addresses.overview()).willReturn(List.of(quiet, full, internal));

        IncidentSnapshot snapshot = service(1000, 1).collect();

        assertEquals(List.of("z-full"), List.copyOf(snapshot.addressSettings().keySet()));
        assertEquals(2, snapshot.addressSettingsOmitted());
        verify(addresses, never()).settings("a-quiet");
    }

    @Test
    @DisplayName("one section the broker refuses leaves the others collected")
    void isolatesSections()
    {
        given(info.acceptors()).willThrow(new ManagementRefusal(Availability.DENIED, "AMQ229032"));
        given(queues.overview()).willThrow(new BrokerException("listQueues failed"));

        IncidentSnapshot snapshot = service(1000, 200).collect();

        assertEquals(Availability.DENIED, snapshot.acceptors().rows().availability());
        assertEquals(Availability.FAILED, snapshot.queues().availability());
        assertTrue(snapshot.connections().rows().available());
        assertTrue(snapshot.addresses().available());
        verify(rates, never()).observe(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("the queue listing is read once and becomes this session's next trend reading")
    void reusesTheQueueListing()
    {
        List<QueueOverview> listed = List
                .of(new QueueOverview("orders", "orders", "ANYCAST", 1, 0, 0, 0, 1, 0, true, false, false));
        given(queues.overview()).willReturn(listed);

        IncidentSnapshot snapshot = service(1000, 200).collect();

        verify(rates).observe(listed);
        assertFalse(snapshot.finishedAt().isBefore(snapshot.startedAt()));
    }

    private SnapshotService service(int maxRows, int maxSettings)
    {
        return new SnapshotService(session, info, queues, addresses, rates, diagnosis, connectivity, maxRows,
                maxSettings);
    }

    private static AddressOverview address(String name, boolean paging, long unrouted)
    {
        return new AddressOverview(name, "ANYCAST", 0, 0, 0, unrouted, paging, false, false, List.of());
    }
}
