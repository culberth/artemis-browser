package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQXAConnectionFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Prepared XA branches for integration tests, made and cleared the way a transaction manager and an operator would.
 *
 * <p>
 * Everything here is test-side. The app never calls {@code commitPreparedTransaction} or
 * {@code rollbackPreparedTransaction} — neither is on its allowlist — and the fixture needs both to stand in for an
 * operator. Resolving a branch through management leaves a journaled heuristic record that no management operation
 * clears, so {@link #clear} also {@code forget}s it through an XA client, leaving the broker as it was.
 */
public final class XaFixtures
{

    /** The Xid format id every fixture branch uses. */
    public static final int FORMAT_ID = 4242;

    private XaFixtures()
    {
    }

    private record TestXid(byte[] global, byte[] branch) implements Xid
    {
        @Override
        public int getFormatId()
        {
            return FORMAT_ID;
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

    private static Xid xid(String name)
    {
        return new TestXid(("gtrid-" + name).getBytes(StandardCharsets.UTF_8),
                "branch-1".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One branch {@code gtrid-<name>} left prepared: inside it two messages sent to {@code target} (the first with the
     * property {@code orderId=o-1}) and — when {@code receive} — one message, put on {@code source} beforehand,
     * received. The connection is then closed without commit or rollback, as a crashed application's would be.
     */
    public static void prepare(String url, String name, String source, String target, boolean receive) throws Exception
    {
        if (receive)
        {
            try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url))
            {
                Connection plain = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
                Session session = plain.createSession(false, Session.AUTO_ACKNOWLEDGE);
                session.createProducer(session.createQueue(source)).send(session.createTextMessage("to take"));
                plain.close();
            }
        }
        try (ActiveMQXAConnectionFactory factory = new ActiveMQXAConnectionFactory(url))
        {
            XAConnection connection = factory.createXAConnection(ArtemisBrokerSupport.USER,
                    ArtemisBrokerSupport.PASSWORD);
            connection.start();
            XASession xa = connection.createXASession();
            XAResource resource = xa.getXAResource();
            Xid xid = xid(name);
            resource.start(xid, XAResource.TMNOFLAGS);
            Session session = xa.getSession();
            MessageProducer producer = session.createProducer(session.createQueue(target));
            Message first = session.createTextMessage("sent in the branch");
            first.setStringProperty("orderId", "o-1");
            producer.send(first);
            producer.send(session.createTextMessage("also sent in the branch"));
            if (receive)
            {
                MessageConsumer consumer = session.createConsumer(session.createQueue(source));
                assertNotNull(consumer.receive(5000), "nothing to receive on " + source);
            }
            resource.end(xid, XAResource.TMSUCCESS);
            assertEquals(XAResource.XA_OK, resource.prepare(xid));
            connection.close();
        }
    }

    /** One branch {@code gtrid-<name>} left prepared after sending {@code count} small messages to {@code target}. */
    public static void prepareMany(String url, String name, String target, int count) throws Exception
    {
        try (ActiveMQXAConnectionFactory factory = new ActiveMQXAConnectionFactory(url))
        {
            XAConnection connection = factory.createXAConnection(ArtemisBrokerSupport.USER,
                    ArtemisBrokerSupport.PASSWORD);
            XASession xa = connection.createXASession();
            XAResource resource = xa.getXAResource();
            Xid xid = xid(name);
            resource.start(xid, XAResource.TMNOFLAGS);
            Session session = xa.getSession();
            MessageProducer producer = session.createProducer(session.createQueue(target));
            for (int i = 0; i < count; i++)
            {
                producer.send(session.createTextMessage("in a large branch " + i));
            }
            resource.end(xid, XAResource.TMSUCCESS);
            assertEquals(XAResource.XA_OK, resource.prepare(xid));
            connection.close();
        }
    }

    /** The base64 Xid of the prepared branch with this global id, read the way an operator would. */
    public static String preparedXid(String url, String globalId) throws Exception
    {
        String details = String.valueOf(manage(url, "listPreparedTransactionDetailsAsJSON"));
        if (!details.isBlank())
        {
            for (JsonNode node : new ObjectMapper().readTree(details))
            {
                if (globalId.equals(node.get("xid_global_txid").asString()))
                {
                    return node.get("xid_as_base64").asString();
                }
            }
        }
        throw new AssertionError("no prepared branch " + globalId + " in " + details);
    }

    /** Commits branch {@code gtrid-<name>} through management, as an operator would; returns its base64 Xid. */
    public static String commitByHand(String url, String name) throws Exception
    {
        String xid = preparedXid(url, "gtrid-" + name);
        assertEquals(Boolean.TRUE, manage(url, "commitPreparedTransaction", xid));
        return xid;
    }

    /**
     * Leaves no trace of branch {@code gtrid-<name>}: rolled back through management if still prepared, then the
     * heuristic record that leaves — or an earlier hand commit left — forgotten through an XA client.
     */
    public static void clear(String url, String name) throws Exception
    {
        try
        {
            manage(url, "rollbackPreparedTransaction", preparedXid(url, "gtrid-" + name));
        }
        catch (AssertionError notPrepared)
        {
            // Already resolved; only its heuristic record is left to forget.
        }
        try (ActiveMQXAConnectionFactory factory = new ActiveMQXAConnectionFactory(url))
        {
            XAConnection connection = factory.createXAConnection(ArtemisBrokerSupport.USER,
                    ArtemisBrokerSupport.PASSWORD);
            try
            {
                connection.createXASession().getXAResource().forget(xid(name));
            }
            catch (javax.transaction.xa.XAException nothingToForget)
            {
                // No heuristic record for it: nothing was resolved by hand.
            }
            finally
            {
                connection.close();
            }
        }
    }

    /** A management operation from the test, not the app. */
    public static Object manage(String url, String operation, Object... params) throws Exception
    {
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url))
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
