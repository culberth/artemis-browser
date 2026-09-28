package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A client is reached through a chain no single listing holds: connections carry the client id, sessions tie a session
 * to its connection, and consumers and producers carry only the session. The fixtures are cut down from 2.44.0 replies:
 * {@code billing-svc} consuming {@code client-q} and producing to {@code client-q} and {@code client-out}, and an
 * anonymous CORE connection consuming the same queue.
 */
class ClientDirectoryTest
{

    private static final String NO_FILTER = "{\"field\":\"\",\"operation\":\"\",\"value\":\"\"}";

    private static final String CONNECTIONS = """
        {"data":[
         {"connectionID":"a018fd3b","remoteAddress":"172.17.0.1:52716","users":"artemis",
          "creationTime":"Mon Sep 28 01:59:40 GMT 2026","protocol":"CORE","clientID":"billing-svc","sessionCount":2},
         {"connectionID":"ea82643f","remoteAddress":"172.17.0.1:52720","users":"artemis",
          "creationTime":"Mon Sep 28 01:59:43 GMT 2026","protocol":"CORE","clientID":"","sessionCount":2}],"count":2}""";

    private static final String SESSIONS = """
        {"data":[
         {"id":"446b9e2f","consumerCount":0,"producerCount":0,"connectionID":"a018fd3b","clientID":"billing-svc",
          "creationTime":"Mon Sep 28 01:59:40 GMT 2026"},
         {"id":"446d24d0","consumerCount":1,"producerCount":2,"connectionID":"a018fd3b","clientID":"billing-svc",
          "creationTime":"Mon Sep 28 01:59:40 GMT 2026"},
         {"id":"463e3b56","consumerCount":1,"producerCount":0,"connectionID":"ea82643f","clientID":"",
          "creationTime":"Mon Sep 28 01:59:43 GMT 2026"}],"count":3}""";

    private static final String CONSUMERS = """
        {"data":[
         {"id":"11","session":"446d24d0","clientID":"billing-svc","queue":"client-q","address":"client-q",
          "filter":"","messagesDelivered":"1","messagesAcknowledged":"0","messagesInTransit":"1"},
         {"id":"12","session":"463e3b56","clientID":"","queue":"client-q","address":"client-q",
          "filter":"","messagesDelivered":"0","messagesAcknowledged":"0","messagesInTransit":"0"}],"count":2}""";

    private static final String PRODUCERS = """
        {"data":[
         {"id":"6","session":"446d24d0","clientID":"billing-svc","address":"client-q",
          "creationTime":"1790560780875","msgSent":1,"msgSizeSent":172},
         {"id":"7","session":"446d24d0","clientID":"billing-svc","address":"client-out",
          "creationTime":"1790560783905","msgSent":1,"msgSizeSent":178}],"count":2}""";

    private BrokerSession brokerSession;
    private ManagementChannel management;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        given(brokerSession.requireManagement()).willReturn(management);
        reply("listConnections", CONNECTIONS);
        reply("listSessions", SESSIONS);
        reply("listConsumers", CONSUMERS);
        reply("listProducers", PRODUCERS);
    }

    @Test
    @DisplayName("a client id reaches its consumers and producers through its sessions, and no one else's")
    void findsAClientById()
    {
        ClientView client = new ClientDirectory(brokerSession).find("billing-svc", null);

        assertTrue(client.named());
        assertEquals("billing-svc", client.title());
        assertEquals(1, client.connections().size());
        assertEquals("CORE", client.connections().get(0).protocol());
        assertEquals(2, client.sessions().size());
        assertEquals(List.of("11"), client.consumers().stream().map(ClientView.Consumer::consumerId).toList(),
                "the anonymous connection's consumer on the same queue is not billing-svc's");
        assertEquals(1, client.inFlight());
        assertEquals(List.of("client-q", "client-out"),
                client.producers().stream().map(ClientView.Producer::address).toList());
        assertFalse(client.producers().get(0).createdText().isEmpty(),
                "listProducers writes its creation time as quoted epoch millis");
    }

    @Test
    @DisplayName("a client with no client id is found by its connection, and named by its remote address")
    void findsAnAnonymousClientByConnection()
    {
        ClientView client = new ClientDirectory(brokerSession).find(null, "ea82643f");

        assertFalse(client.named());
        assertEquals("172.17.0.1:52720", client.title());
        assertEquals(List.of("12"), client.consumers().stream().map(ClientView.Consumer::consumerId).toList());
        assertTrue(client.producers().isEmpty());
    }

    @Test
    @DisplayName("a client that is not connected is null, and asking for nobody reads nothing")
    void missingClientIsNull()
    {
        assertNull(new ClientDirectory(brokerSession).find("gone-svc", null));

        ManagementChannel untouched = mock(ManagementChannel.class);
        BrokerSession idle = mock(BrokerSession.class);
        given(idle.requireManagement()).willReturn(untouched);
        assertNull(new ClientDirectory(idle).find(" ", ""));
        verifyNoInteractions(untouched);
    }

    private void reply(String operation, String json)
    {
        given(management.invoke(ResourceNames.BROKER, operation, NO_FILTER, 1, 200)).willReturn(json);
    }
}
