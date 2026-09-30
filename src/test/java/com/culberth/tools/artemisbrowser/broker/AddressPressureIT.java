package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.broker.AddressPressure.State;
import com.culberth.tools.artemisbrowser.broker.AddressSettings.FullPolicy;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An address past its limit under each full policy, on a real broker — the only way to know what the broker reports
 * then, since none of it is what the field names suggest: under FAIL and DROP a full address reports {@code paging}
 * with no pages, and under BLOCK it passes 100% with {@code paging} false.
 *
 * <p>
 * Each address gets a 20KB limit through management as test setup, which the app itself refuses to do, and is sent 40
 * one-kilobyte messages. {@code pageSizeBytes} is lowered with it: the broker rejects a {@code maxSizeBytes} below the
 * page size, 10MB by default.
 */
class AddressPressureIT
{

    private static final String PAGE = "it-press-page";
    private static final String BLOCK = "it-press-block";
    private static final String FAIL = "it-press-fail";
    private static final String DROP = "it-press-drop";
    /** Blocked by an operator through management, with room to spare. */
    private static final String HELD = "it-press-held";

    private static final int MESSAGES = 40;
    private static final String BODY = "x".repeat(1000);

    private static BrokerSession brokerSession;
    private static QueueDirectory queues;
    private static AddressDirectory addresses;

    @BeforeAll
    static void fillAddresses() throws Exception
    {
        brokerSession = ArtemisBrokerSupport.connect();
        queues = new QueueDirectory(brokerSession);
        addresses = new AddressDirectory(brokerSession, queues);
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url()))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            try
            {
                connection.start();
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                for (String address : List.of(PAGE, BLOCK, FAIL, DROP))
                {
                    String policy = address.substring("it-press-".length()).toUpperCase();
                    manage(session, ResourceNames.BROKER, "addAddressSettings", address,
                            "{\"maxSizeBytes\":20000,\"pageSizeBytes\":10000,\"addressFullMessagePolicy\":\"" + policy
                                    + "\"}");
                }
                fill(session, PAGE);
                fill(session, FAIL);
                fill(session, DROP);
                send(session, HELD, 1);
                manage(session, ResourceNames.ADDRESS + HELD, "block");
            }
            finally
            {
                connection.close();
            }
        }
        fillBlocking();
    }

    @AfterAll
    static void disconnect()
    {
        if (brokerSession != null)
        {
            brokerSession.close();
        }
    }

    @Test
    @DisplayName("the listing reports each policy's full address as recorded: paging means over the limit")
    void readsTheListing()
    {
        AddressOverview page = addresses.find(PAGE);
        assertTrue(page.pagedToDisk(), "PAGE writes pages");
        assertTrue(page.measuredLimitPercent() >= 100);

        AddressOverview fail = addresses.find(FAIL);
        assertTrue(fail.fullWithoutPaging(), "FAIL reports paging with no pages");
        assertTrue(fail.messageCount() < MESSAGES, "FAIL refused some sends");

        AddressOverview drop = addresses.find(DROP);
        assertTrue(drop.fullWithoutPaging());
        assertTrue(drop.routedMessageCount() > drop.messageCount(), "DROP accepted what it did not keep");

        AddressOverview block = addresses.find(BLOCK);
        assertFalse(block.paging(), "BLOCK does not set the paging flag");
        assertTrue(block.atByteLimit(), "BLOCK passes its limit: " + block.limitPercent());
    }

    @Test
    @DisplayName("the address page puts each usage beside its policy, and paging under PAGE is normal")
    void classifiesEachAddress()
    {
        AddressDetailService detail = new AddressDetailService(addresses, queues, new BrokerInfoService(brokerSession),
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L),
                new DivertDirectory(brokerSession),
                new InFlightService(brokerSession, new BrokerInfoService(brokerSession), 5000));

        assertEquals(State.PAGING, detail.detail(PAGE).pressure().state());
        assertEquals(State.AT_LIMIT, detail.detail(BLOCK).pressure().state());
        assertEquals(State.AT_LIMIT, detail.detail(FAIL).pressure().state());
        assertEquals(State.AT_LIMIT, detail.detail(DROP).pressure().state());
        assertEquals(State.BLOCKED, detail.detail(HELD).pressure().state());

        AddressPressure block = detail.detail(BLOCK).pressure();
        assertEquals(FullPolicy.BLOCK, block.policy());
        assertEquals(20000, block.byteLimit().value());
        assertFalse(block.blocked().value(), "a policy block is not a management block");
        assertNotNull(block.globalText(), "the global limit is read beside the address");
    }

    @Test
    @DisplayName("diagnose names each address, marks the operator's block observed and the rest inferred")
    void diagnosesPressure()
    {
        List<Finding> findings = new StuckDiagnosisService(queues, addresses, new BrokerInfoService(brokerSession),
                new QueueBrowseService(brokerSession, 200, 200000, 20000, 20_000_000L),
                new DivertDirectory(brokerSession),
                new InFlightService(brokerSession, new BrokerInfoService(brokerSession), 5000),
                new RateService(brokerSession, new RateTracker(), new QueueDirectory(brokerSession)),
                new ConnectivityService(brokerSession), new TransactionService(brokerSession, 100)).diagnose(false);

        Finding held = about(findings, HELD);
        assertTrue(held.isStuck());
        assertFalse(held.isInferred());

        for (String address : List.of(BLOCK, FAIL, DROP))
        {
            Finding finding = about(findings, address);
            assertTrue(finding.isStuck() && finding.isInferred(), finding.toString());
        }
        assertTrue(findings.stream().noneMatch(f -> PAGE.equals(f.address())), "paging under PAGE is not a fault");
    }

    private static Finding about(List<Finding> findings, String address)
    {
        return findings.stream().filter(f -> address.equals(f.address())).findFirst()
                .orElseThrow(() -> new AssertionError("no finding for " + address + " in " + findings));
    }

    private static void fill(Session session, String queue) throws JMSException
    {
        try (MessageProducer producer = session.createProducer(session.createQueue(queue)))
        {
            for (int i = 0; i < MESSAGES; i++)
            {
                try
                {
                    producer.send(session.createTextMessage(BODY));
                }
                catch (JMSException full)
                {
                    // FAIL answers "address is full"; that is the behaviour under test.
                    return;
                }
            }
        }
    }

    private static void send(Session session, String queue, int count) throws JMSException
    {
        try (MessageProducer producer = session.createProducer(session.createQueue(queue)))
        {
            for (int i = 0; i < count; i++)
            {
                producer.send(session.createTextMessage(queue));
            }
        }
    }

    /**
     * Under BLOCK the sender waits for credit that never comes, so it sends from its own connection on another thread
     * and is cut off after a few seconds — by which time the broker has taken all it will.
     */
    private static void fillBlocking() throws Exception
    {
        ExecutorService sender = Executors.newSingleThreadExecutor();
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url()))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            Future<?> sending = sender.submit(() ->
            {
                fill(connection.createSession(false, Session.AUTO_ACKNOWLEDGE), BLOCK);
                return null;
            });
            try
            {
                sending.get(5, TimeUnit.SECONDS);
            }
            catch (java.util.concurrent.TimeoutException expected)
            {
                // Blocked, as BLOCK means.
            }
            finally
            {
                sending.cancel(true);
                try
                {
                    connection.close();
                }
                catch (JMSException ignored)
                {
                    // Closing under a blocked send may complain; the broker side is what is measured.
                }
            }
        }
        finally
        {
            sender.shutdownNow();
        }
    }

    /** Test setup through the management address directly; the app's own channel refuses anything but a read. */
    private static void manage(Session session, String resource, String operation, Object... params) throws Exception
    {
        TemporaryQueue reply = session.createTemporaryQueue();
        try (MessageProducer producer = session.createProducer(session.createQueue("activemq.management"));
                MessageConsumer consumer = session.createConsumer(reply))
        {
            Message request = session.createMessage();
            JMSManagementHelper.putOperationInvocation(request, resource, operation, params);
            request.setJMSReplyTo(reply);
            producer.send(request);
            Message answer = consumer.receive(10000);
            if (answer == null || !JMSManagementHelper.hasOperationSucceeded(answer))
            {
                throw new IllegalStateException(operation + " failed: "
                        + (answer == null ? "no answer" : JMSManagementHelper.getResult(answer)));
            }
        }
        finally
        {
            reply.delete();
        }
    }
}
