package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.XAConnection;
import jakarta.jms.XASession;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQXAConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Prepared XA transactions on a real broker, and what reading them does — which must be nothing.
 *
 * <p>
 * Its own broker, not {@link ArtemisBrokerSupport}'s: a prepared branch outlives the connection that made it and holds
 * a message as delivering with no consumer, which the shared broker's diagnose tests would rightly report. Two branches
 * are made the way a transaction manager would leave them after a crash — prepared, connection closed — and one is then
 * committed through management by this test, as an operator would, to put something in the heuristic list. The app
 * never calls either operation; it cannot, since neither is on its allowlist.
 */
class TransactionsIT
{

    static final String SOURCE = "it-xa.src";
    static final String TARGET = "it-xa.dst";

    private static GenericContainer<?> container;
    private static BrokerSession brokerSession;
    private static String committedXid;

    @BeforeAll
    static void start() throws Exception
    {
        container = new GenericContainer<>(DockerImageName.parse(ArtemisBrokerSupport.IMAGE))
                .withEnv("ARTEMIS_USER", ArtemisBrokerSupport.USER)
                .withEnv("ARTEMIS_PASSWORD", ArtemisBrokerSupport.PASSWORD).withExposedPorts(61616)
                .waitingFor(Wait.forLogMessage(".*Server is now active.*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(4));
        container.start();

        prepare("held", true);
        prepare("resolved", false);
        committedXid = heldBy("gtrid-resolved");
        assertEquals(Boolean.TRUE, manage("commitPreparedTransaction", committedXid));

        brokerSession = new BrokerSession(10000, 10000);
        brokerSession.connect(new BrokerCredentials(container.getHost(), container.getMappedPort(61616),
                ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD));
    }

    @AfterAll
    static void stop()
    {
        if (brokerSession != null)
        {
            brokerSession.close();
        }
        if (container != null)
        {
            container.stop();
        }
    }

    @Test
    @DisplayName("a prepared branch is read with its Xid, what it received and sent, and an age from the broker's clock")
    void readsAPreparedBranch()
    {
        Transactions transactions = new TransactionService(brokerSession, 100).collect();

        assertTrue(transactions.prepared().available(), transactions.prepared().explained());
        assertEquals(1, transactions.preparedTotal());
        PreparedTransaction tx = transactions.prepared().value().get(0);
        assertEquals("gtrid-held", tx.globalId());
        assertEquals("branch-1", tx.branch());
        assertEquals(4242, tx.formatId());
        assertEquals(2, tx.sends());
        assertEquals(1, tx.receives());
        assertEquals(1, transactions.heldFrom(SOURCE));
        assertEquals(List.of(SOURCE, TARGET), tx.addresses());
        assertEquals("o-1", tx.messages().stream().filter(TransactionMessage::send).findFirst().orElseThrow()
                .properties().get("orderId"));
        // The broker's zone worked out from its own listings: the age is minutes, not hours off.
        assertNotNull(tx.created(), tx.createdNote());
        long age = tx.ageMillis(Instant.now());
        assertTrue(age >= 0 && age < 5 * 60_000, "age " + age + " from " + tx.createdText());
    }

    @Test
    @DisplayName("a branch committed through management is listed as resolved by hand, and is no longer prepared")
    void readsAHeuristicCommit()
    {
        Transactions transactions = new TransactionService(brokerSession, 100).collect();

        assertEquals(List.of(committedXid), transactions.heuristicCommitted().value());
        assertTrue(transactions.heuristicRolledBack().value().isEmpty());
        assertFalse(transactions.prepared().value().stream().anyMatch(tx -> tx.xid().equals(committedXid)));
    }

    @Test
    @DisplayName("past the detail limit the summary is read, with the same Xid and creation time")
    void readsTheSummaryPastTheLimit()
    {
        Transactions transactions = new TransactionService(brokerSession, 0).collect();

        PreparedTransaction tx = transactions.prepared().value().get(0);
        assertFalse(transactions.detailRead());
        assertEquals(heldBy("gtrid-held"), tx.xid());
        assertNotNull(tx.created(), tx.createdNote());
    }

    @Test
    @DisplayName("the held message is on its queue as delivering with no consumer, and diagnose says what holds it")
    void diagnosesTheHeldMessage()
    {
        QueueOverview source = new QueueDirectory(brokerSession).overview().stream()
                .filter(q -> q.name().equals(SOURCE)).findFirst().orElseThrow();
        assertEquals(1, source.messageCount());
        assertEquals(1, source.deliveringCount());
        assertEquals(0, source.consumerCount());

        List<Finding> findings = diagnosis().diagnose(false);

        Finding queue = findings.stream().filter(f -> SOURCE.equals(f.queue())).findFirst().orElseThrow();
        assertTrue(queue.title().contains("in delivery with no consumer attached"), queue.title());
        assertTrue(queue.hasExplanation() && queue.explanation().contains("prepared XA"), String.valueOf(queue));
        Finding tx = findings.stream().filter(f -> f.title().startsWith("Prepared XA transaction gtrid-held"))
                .findFirst().orElseThrow(() -> new AssertionError(findings.toString()));
        // Prepared moments ago in this fixture, so it may be a transaction manager between prepare and
        // commit: worth a look, explained, not yet "not moving". The older case is TransactionServiceTest's.
        assertFalse(tx.isStuck());
        assertTrue(tx.hasExplanation(), String.valueOf(tx));
        assertEquals(SOURCE, tx.address());
        assertTrue(findings.stream().anyMatch(f -> f.title().contains("resolved by hand")), findings.toString());
    }

    @Test
    @DisplayName("roles are read for an address, existing or not, from the matching security setting")
    void readsRoles()
    {
        Permissions permissions = new PermissionService(brokerSession).forAddresses(List.of(SOURCE, "no-such-address"),
                10);

        assertEquals(Boolean.TRUE, permissions.securityEnabled().value());
        for (String address : List.of(SOURCE, "no-such-address"))
        {
            RoleGrant amq = permissions.of(address).value().stream().filter(g -> g.role().equals("amq")).findFirst()
                    .orElseThrow();
            assertEquals(Boolean.TRUE, amq.allows("send"));
            assertEquals(Boolean.TRUE, amq.allows("consume"));
            assertEquals(Boolean.FALSE, amq.allows("manage"));
        }
        assertEquals(List.of("amq"), new PermissionService(brokerSession).forAddress("activemq.management")
                .rolesThatMay("activemq.management", "manage"));
    }

    @Test
    @DisplayName("reading transactions, roles, diagnose and a snapshot changes no counter and resolves nothing")
    void readingChangesNothing()
    {
        Map<String, String> before = counters();
        List<String> preparedBefore = prepared();

        for (int round = 0; round < 3; round++)
        {
            new TransactionService(brokerSession, 100).collect();
            new TransactionService(brokerSession, 0).collect();
            new PermissionService(brokerSession).forAddresses(List.of(SOURCE, TARGET), 10);
            diagnosis().run(true);
            snapshots().collect();
        }

        assertEquals(before, counters(), "a read moved a counter");
        assertEquals(preparedBefore, prepared(), "a read changed what is prepared");
    }

    @Test
    @DisplayName("a snapshot carries the prepared branch and the roles, and no message property")
    void snapshotsTransactions()
    {
        IncidentSnapshot snapshot = snapshots().collect();
        String json = SnapshotWriter.toJson(snapshot).toString();

        assertEquals(1, snapshot.transactions().value().preparedTotal());
        assertTrue(json.contains("\"globalTransactionId\":\"gtrid-held\""), json);
        assertTrue(json.contains("\"byAddress\""), json);
        assertFalse(json.contains("orderId"), "a message property reached the snapshot");
    }

    // ------------------------------------------------------------------ helpers

    private static StuckDiagnosisService diagnosis()
    {
        QueueDirectory queues = new QueueDirectory(brokerSession);
        BrokerInfoService info = new BrokerInfoService(brokerSession);
        return new StuckDiagnosisService(queues, new AddressDirectory(brokerSession, queues), info,
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L),
                new DivertDirectory(brokerSession), new InFlightService(brokerSession, info, 5000),
                new RateService(brokerSession, new RateTracker(), queues), new ConnectivityService(brokerSession),
                new TransactionService(brokerSession, 100));
    }

    private static SnapshotService snapshots()
    {
        QueueDirectory queues = new QueueDirectory(brokerSession);
        BrokerInfoService info = new BrokerInfoService(brokerSession);
        RateService rates = new RateService(brokerSession, new RateTracker(), queues);
        return new SnapshotService(brokerSession, info, queues, new AddressDirectory(brokerSession, queues), rates,
                diagnosis(), new ConnectivityService(brokerSession), new TransactionService(brokerSession, 100),
                new PermissionService(brokerSession), 1000, 200, 200);
    }

    private Map<String, String> counters()
    {
        Map<String, String> counters = new LinkedHashMap<>();
        for (QueueOverview queue : new QueueDirectory(brokerSession).overview())
        {
            if (queue.name().startsWith("it-"))
            {
                counters.put(queue.name(), queue.messageCount() + "/" + queue.deliveringCount() + "/"
                        + queue.messagesAcked() + "/" + queue.messagesAdded());
            }
        }
        assertFalse(counters.isEmpty(), "no it- queues on the broker");
        return counters;
    }

    private List<String> prepared()
    {
        return new TransactionService(brokerSession, 100).collect().prepared().value().stream()
                .map(tx -> tx.xid() + "/" + tx.messageTotal()).toList();
    }

    private static String url()
    {
        return "tcp://" + container.getHost() + ":" + container.getMappedPort(61616)
                + "?useTopologyForLoadBalancing=false";
    }

    private record TestXid(byte[] global, byte[] branch) implements Xid
    {
        @Override
        public int getFormatId()
        {
            return 4242;
        }

        @Override
        public byte[] getGlobalTransactionId()
        {
            return global;
        }

        @Override
        public byte[] getBranchQualifier()
        {
            return branch;
        }
    }

    /**
     * One branch left prepared: when {@code receive}, a message put on the source queue beforehand; then inside the
     * branch two sent to the target and — when {@code receive} — that one received. The connection is closed without
     * commit or rollback, as a crashed application's would be.
     */
    private static void prepare(String name, boolean receive) throws Exception
    {
        if (receive)
        {
            try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url()))
            {
                Connection plain = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
                Session session = plain.createSession(false, Session.AUTO_ACKNOWLEDGE);
                session.createProducer(session.createQueue(SOURCE)).send(session.createTextMessage("to take"));
                plain.close();
            }
        }
        try (ActiveMQXAConnectionFactory factory = new ActiveMQXAConnectionFactory(url()))
        {
            XAConnection connection = factory.createXAConnection(ArtemisBrokerSupport.USER,
                    ArtemisBrokerSupport.PASSWORD);
            connection.start();
            XASession xa = connection.createXASession();
            XAResource resource = xa.getXAResource();
            Xid xid = new TestXid(("gtrid-" + name).getBytes(StandardCharsets.UTF_8),
                    "branch-1".getBytes(StandardCharsets.UTF_8));
            resource.start(xid, XAResource.TMNOFLAGS);
            Session session = xa.getSession();
            MessageProducer producer = session.createProducer(session.createQueue(TARGET));
            Message first = session.createTextMessage("sent in the branch");
            first.setStringProperty("orderId", "o-1");
            producer.send(first);
            producer.send(session.createTextMessage("also sent in the branch"));
            if (receive)
            {
                MessageConsumer consumer = session.createConsumer(session.createQueue(SOURCE));
                assertNotNull(consumer.receive(5000), "nothing to receive on " + SOURCE);
            }
            resource.end(xid, XAResource.TMSUCCESS);
            assertEquals(XAResource.XA_OK, resource.prepare(xid));
            connection.close();
        }
    }

    /** The base64 Xid of the prepared branch with this global id, read the way an operator would. */
    private static String heldBy(String globalId)
    {
        try
        {
            String details = String.valueOf(manage("listPreparedTransactionDetailsAsJSON"));
            for (tools.jackson.databind.JsonNode node : new tools.jackson.databind.ObjectMapper().readTree(details))
            {
                if (globalId.equals(node.get("xid_global_txid").asString()))
                {
                    return node.get("xid_as_base64").asString();
                }
            }
            throw new AssertionError("no prepared branch " + globalId + " in " + details);
        }
        catch (AssertionError e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A management operation from the test, not the app — {@code commitPreparedTransaction} is exactly what the app's
     * allowlist refuses, and the fixture needs it to stand in for an operator.
     */
    private static Object manage(String operation, Object... params) throws Exception
    {
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url()))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            try
            {
                connection.start();
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                TemporaryQueue reply = session.createTemporaryQueue();
                MessageProducer producer = session.createProducer(session.createQueue("activemq.management"));
                MessageConsumer consumer = session.createConsumer(reply);
                Message request = session.createMessage();
                JMSManagementHelper.putOperationInvocation(request, ResourceNames.BROKER, operation, params);
                request.setJMSReplyTo(reply);
                producer.send(request);
                Message answer = consumer.receive(10000);
                assertNotNull(answer, "no answer to " + operation);
                assertTrue(JMSManagementHelper.hasOperationSucceeded(answer),
                        operation + ": " + JMSManagementHelper.getResult(answer));
                return JMSManagementHelper.getResult(answer);
            }
            finally
            {
                connection.close();
            }
        }
    }
}
