package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prepared XA transactions as the broker reports them, parsed from the replies recorded against 2.55.0 and 2.57.0: a
 * branch that sent two messages to {@code p6.xa} and received one from {@code p6.xa.src}, left prepared by closing its
 * connection. The replies are copied from {@code .claude/memory.md}, not invented.
 */
class TransactionServiceTest
{

    /** A narrow no-break space before AM, as a current JDK writes it. */
    private static final String CREATED = "9/30/26, 7:41:25 AM";

    private static final String XID = "YnJhbmNoLTFndHJpZC1wNi54YaIQAAA=";

    private static final String SUMMARY = CREATED + " base64: " + XID + " XidImpl (1258384728 bq:98.114.97.110.99"
            + ".104.45.49 formatID:4242 gtxid:103.116.114.105.100.45.112.54.46.120.97 base64:" + XID;

    private static final String DETAILS = "[{\"creation_time\":\"" + CREATED + "\",\"xid_as_base64\":\"" + XID
            + "\",\"xid_format_id\":4242,\"xid_global_txid\":\"gtrid-p6.xa\",\"xid_branch_qual\":\"branch-1\","
            + "\"tx_related_messages\":[{\"message_operation_type\":\"(+) send\",\"message_type\":\"TextMessage\","
            + "\"message_properties\":{\"durable\":true,\"address\":\"p6.xa\",\"__AMQ_CID\":\"56ab5988\","
            + "\"orderId\":\"o-1\",\"_AMQ_ROUTING_TYPE\":1,\"messageID\":2147486528,\"expiration\":0,\"type\":3,"
            + "\"priority\":4,\"userID\":\"ID:56adf19b-bca2-11f1-a295-00155d348692\",\"timestamp\":1790754085016}},"
            + "{\"message_operation_type\":\"(+) send\",\"message_type\":\"TextMessage\",\"message_properties\":"
            + "{\"durable\":true,\"address\":\"p6.xa\",\"messageID\":2147486529,\"priority\":4,"
            + "\"userID\":\"ID:56ae18ac-bca2-11f1-a295-00155d348692\",\"timestamp\":1790754085017}},"
            + "{\"message_operation_type\":\"(-) receive\",\"message_type\":\"TextMessage\",\"message_properties\":"
            + "{\"durable\":true,\"address\":\"p6.xa.src\",\"messageID\":2147486514,\"priority\":4,"
            + "\"userID\":\"ID:56a761e5-bca2-11f1-a295-00155d348692\",\"timestamp\":1790754084973}}]}]";

    private static final String NO_FILTER = "{\"field\":\"\",\"operation\":\"\",\"value\":\"\"}";

    /** 07:41:24 UTC, as {@code listConnections} writes it on a broker whose zone is GMT. */
    private static final String CONNECTIONS_PAGE = "{\"data\":[{\"connectionID\":\"c1\",\"remoteAddress\":"
            + "\"172.17.0.1:5000\",\"users\":\"artemis\",\"protocol\":\"CORE\",\"clientID\":\"\","
            + "\"creationTime\":\"Wed Sep 30 07:41:24 GMT 2026\",\"sessionCount\":1}],\"count\":1}";
    private static final String CONNECTIONS_JSON = "[{\"connectionID\":\"c1\",\"clientAddress\":\"/172.17.0.1:5000\","
            + "\"creationTime\":1790754084123,\"implementation\":\"RemotingConnectionImpl\",\"sessionCount\":1}]";

    private BrokerSession brokerSession;
    private ManagementChannel management;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        given(brokerSession.requireManagement()).willReturn(management);
        given(management.invoke(ResourceNames.BROKER, "listPreparedTransactions")).willReturn(new Object[0]);
        given(management.invoke(ResourceNames.BROKER, "listHeuristicCommittedTransactions")).willReturn(new Object[0]);
        given(management.invoke(ResourceNames.BROKER, "listHeuristicRolledBackTransactions")).willReturn(new Object[0]);
        given(management.invoke(ResourceNames.BROKER, "listConnections", NO_FILTER, 1, 1)).willReturn(CONNECTIONS_PAGE);
        given(management.invoke(ResourceNames.BROKER, "listConnectionsAsJSON")).willReturn(CONNECTIONS_JSON);
    }

    private void onePrepared()
    {
        given(management.invoke(ResourceNames.BROKER, "listPreparedTransactions")).willReturn(new Object[]
        { SUMMARY
        });
        given(management.invoke(ResourceNames.BROKER, "listPreparedTransactionDetailsAsJSON")).willReturn(DETAILS);
    }

    @Test
    @DisplayName("nothing prepared reads as an empty list, and the detail and clock reads are not made")
    void readsNothingPrepared()
    {
        Transactions transactions = service(100).collect();

        assertTrue(transactions.prepared().available());
        assertFalse(transactions.any());
        assertEquals(0, transactions.preparedTotal());
        assertEquals(0, transactions.heuristicCount());
        verify(management, never()).invoke(ResourceNames.BROKER, "listPreparedTransactionDetailsAsJSON");
        verify(management, never()).invoke(ResourceNames.BROKER, "listConnectionsAsJSON");
    }

    @Test
    @DisplayName("a prepared branch is read with its Xid, its sends and receives, and the message properties it carries")
    void readsAPreparedBranch()
    {
        onePrepared();

        Transactions transactions = service(100).collect();

        PreparedTransaction tx = transactions.prepared().value().get(0);
        assertEquals(XID, tx.xid());
        assertEquals(4242, tx.formatId());
        assertEquals("gtrid-p6.xa / branch-1", tx.title());
        assertEquals(2, tx.sends());
        assertEquals(1, tx.receives());
        assertEquals(1, transactions.heldFrom("p6.xa.src"));
        assertEquals(0, transactions.heldFrom("p6.xa"));
        assertEquals(List.of("p6.xa.src", "p6.xa"), tx.addresses());
        TransactionMessage first = tx.messages().get(0);
        assertEquals("ID:56adf19b-bca2-11f1-a295-00155d348692", first.displayId());
        assertEquals(java.util.Map.of("orderId", "o-1"), first.properties());
        assertEquals(1, transactions.touching("p6.xa.src").size());
    }

    @Test
    @DisplayName("the creation time becomes an instant through the broker's own zone, worked out from its connections")
    void readsTheCreationTime()
    {
        onePrepared();

        PreparedTransaction tx = service(100).collect().prepared().value().get(0);

        assertEquals(Instant.parse("2026-09-30T07:41:25Z"), tx.created());
        assertEquals(CREATED, tx.createdText());
        assertEquals("", tx.createdNote());
    }

    @Test
    @DisplayName("a broker seven hours behind UTC is read as that, not as UTC")
    void readsAnOffset()
    {
        TransactionService.BrokerClock clock = TransactionService.offset("Wed Sep 30 00:41:24 PDT 2026",
                1790754084123L);

        assertEquals(ZoneOffset.ofHours(-7), clock.offset());
        assertEquals(Instant.parse("2026-09-30T07:41:25Z"), TransactionService.created("9/30/26, 12:41:25 AM", clock));
    }

    @Test
    @DisplayName("when the zone cannot be worked out, the time is kept as written and no age is claimed")
    void keepsTheTimeWithoutAZone()
    {
        onePrepared();
        given(management.invoke(ResourceNames.BROKER, "listConnections", NO_FILTER, 1, 1))
                .willThrow(new ManagementRefusal(Availability.DENIED, "AMQ229032 listConnections"));

        PreparedTransaction tx = service(100).collect().prepared().value().get(0);

        assertNull(tx.created());
        assertNull(tx.ageMillis(Instant.now()));
        assertEquals(CREATED, tx.createdText());
        assertTrue(tx.createdNote().contains("time zone"), tx.createdNote());
    }

    @Test
    @DisplayName("a creation time in another locale is kept as written, with no age")
    void keepsAnotherLocale()
    {
        TransactionService.BrokerClock clock = TransactionService.offset("Wed Sep 30 07:41:24 GMT 2026",
                1790754084123L);

        assertNull(TransactionService.created("30.09.26, 07:41:25", clock));
    }

    @Test
    @DisplayName("past the detail limit only the summary lines are read, and the page is told why")
    void readsSummariesPastTheLimit()
    {
        given(management.invoke(ResourceNames.BROKER, "listPreparedTransactions")).willReturn(new Object[]
        { SUMMARY, SUMMARY.replace(XID, "b3RoZXI=")
        });

        Transactions transactions = service(1).collect();

        assertEquals(2, transactions.preparedTotal());
        assertFalse(transactions.detailRead());
        assertTrue(transactions.detailSkipped().contains("artemis.transactions.detail-limit"));
        PreparedTransaction tx = transactions.prepared().value().get(1);
        assertEquals("b3RoZXI=", tx.xid());
        assertEquals(CREATED, tx.createdText());
        assertEquals(Instant.parse("2026-09-30T07:41:25Z"), tx.created());
        assertFalse(tx.detailRead());
        verify(management, never()).invoke(ResourceNames.BROKER, "listPreparedTransactionDetailsAsJSON");
    }

    @Test
    @DisplayName("a branch with more messages than are kept says how many there were")
    void capsMessagesPerBranch()
    {
        onePrepared();
        StringBuilder many = new StringBuilder(
                "[{\"creation_time\":\"\",\"xid_as_base64\":\"x\"," + "\"tx_related_messages\":[");
        for (int i = 0; i < 60; i++)
        {
            many.append(i == 0 ? "" : ",").append("{\"message_operation_type\":\"(+) send\",\"message_properties\":"
                    + "{\"address\":\"a\",\"messageID\":" + i + "}}");
        }
        given(management.invoke(ResourceNames.BROKER, "listPreparedTransactionDetailsAsJSON"))
                .willReturn(many.append("]}]").toString());

        PreparedTransaction tx = service(100).collect().prepared().value().get(0);

        assertEquals(TransactionService.MESSAGE_LIMIT, tx.messages().size());
        assertEquals(60, tx.messageTotal());
        assertTrue(tx.truncated());
    }

    @Test
    @DisplayName("an empty string from the detail call, as the broker gives when nothing is prepared, is no branches")
    void readsAnEmptyString()
    {
        given(management.invoke(ResourceNames.BROKER, "listPreparedTransactions")).willReturn(new Object[]
        { SUMMARY
        });
        given(management.invoke(ResourceNames.BROKER, "listPreparedTransactionDetailsAsJSON")).willReturn("");

        assertTrue(service(100).collect().prepared().value().isEmpty());
    }

    @Test
    @DisplayName("a refused listing is 'denied', and the heuristic lists are still read")
    void isolatesARefusal()
    {
        given(management.invoke(ResourceNames.BROKER, "listPreparedTransactions"))
                .willThrow(new ManagementRefusal(Availability.DENIED, "AMQ229032"));
        given(management.invoke(ResourceNames.BROKER, "listHeuristicCommittedTransactions")).willReturn(new Object[]
        { "YnJhbmNoLTFndHJpZC1wNi54YqIQAAA="
        });

        Transactions transactions = service(100).collect();

        assertEquals(Availability.DENIED, transactions.prepared().availability());
        assertEquals(List.of("YnJhbmNoLTFndHJpZC1wNi54YqIQAAA="), transactions.heuristicCommitted().value());
    }

    @Test
    @DisplayName("a malformed detail reply fails the listing, not the page")
    void failsOnMalformed()
    {
        onePrepared();
        given(management.invoke(ResourceNames.BROKER, "listPreparedTransactionDetailsAsJSON")).willReturn("{oops");

        assertEquals(Availability.FAILED, service(100).collect().prepared().availability());
    }

    // ------------------------------------------------------------------ findings

    @Test
    @DisplayName("a branch holding a received message for ten minutes is not moving, and names its addresses")
    void findsAHeldBranch()
    {
        List<Finding> findings = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();

        TransactionService.findings(TransactionFixtures.held("p6.src", "p6.dst"), Instant.now(), findings, unchecked);

        Finding prepared = findings.get(0);
        assertTrue(prepared.isStuck());
        assertEquals("p6.src", prepared.address());
        assertTrue(prepared.detail().contains("1 from 'p6.src'"), prepared.detail());
        assertTrue(prepared.detail().contains("2 to 'p6.dst'"), prepared.detail());
        assertTrue(prepared.detail().contains("10m"), prepared.detail());
        assertTrue(findings.get(1).title().contains("resolved by hand"), findings.toString());
        assertTrue(unchecked.isEmpty());
    }

    @Test
    @DisplayName("a branch prepared seconds ago may be a transaction manager mid-commit, and says so")
    void explainsARecentBranch()
    {
        Instant now = Instant.now();
        Transactions recent = new Transactions(
                Reading.of(List.of(TransactionFixtures.branch("p6.src", "p6.dst", now.minusSeconds(5)))), 1, "",
                Reading.of(List.of()), Reading.of(List.of()), "", now);
        List<Finding> findings = new ArrayList<>();

        TransactionService.findings(recent, now, findings, new ArrayList<>());

        assertFalse(findings.get(0).isStuck());
        assertTrue(findings.get(0).hasExplanation());
    }

    @Test
    @DisplayName("listings it could not read are named as not checked, never as nothing prepared")
    void namesWhatItCouldNotCheck()
    {
        List<Finding> findings = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();

        TransactionService.findings(TransactionFixtures.denied(), Instant.now(), findings, unchecked);

        assertTrue(findings.isEmpty());
        assertTrue(unchecked.stream().anyMatch(line -> line.startsWith("Prepared XA transactions")),
                unchecked.toString());
        assertTrue(unchecked.stream().anyMatch(line -> line.contains("resolved by hand")), unchecked.toString());
    }

    @Test
    @DisplayName("nothing prepared and nothing resolved by hand finds nothing")
    void findsNothing()
    {
        List<Finding> findings = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();

        TransactionService.findings(TransactionFixtures.none(), Instant.now(), findings, unchecked);

        assertTrue(findings.isEmpty());
        assertTrue(unchecked.isEmpty());
    }

    private TransactionService service(int detailLimit)
    {
        return new TransactionService(brokerSession, detailLimit);
    }
}
