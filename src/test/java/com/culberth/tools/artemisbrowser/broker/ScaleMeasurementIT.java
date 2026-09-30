package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.culberth.tools.artemisbrowser.compare.SnapshotComparer;
import com.culberth.tools.artemisbrowser.compare.SnapshotFile;
import com.culberth.tools.artemisbrowser.compare.SnapshotReader;
import com.culberth.tools.artemisbrowser.filter.SavedSearch;
import com.culberth.tools.artemisbrowser.filter.SavedSearchStore;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.Topic;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * What the Phase 13 pages and the Phase 14 workflows cost on a large broker: time, response size and management calls
 * per page, the largest transaction reply, a session's trend memory at its cap, and what comparing two of its snapshots
 * holds in memory. A measurement, not a regression test — it prints a table for the README and PRD and asserts only the
 * bounds the design promises: a comparison makes no management call, and a lookup finds a message on the last queue.
 *
 * <p>
 * Opt-in, since seeding takes minutes: {@code mvn verify -Pintegration -Dit.test=ScaleMeasurementIT -Dmeasure=true
 * -Dit.newest.skip=true}. Its own broker: 1,000 queues of 20 messages, 100 multicast addresses with 3 durable
 * subscriptions of 10 each, and 150 prepared XA branches — past the 100 whose details a page reads. Times are the
 * application's own, through MockMvc in the test JVM against a broker in a local container; there is no HTTP hop, so a
 * browser sees a few milliseconds more.
 */
@SpringBootTest(properties =
{ "server.address=127.0.0.1", "artemis.auth.username=", "artemis.auth.password-hash=",
        "artemis.connections-file=${java.io.tmpdir}/artemis-browser-scale-it.json",
        "artemis.saved-searches.file=${java.io.tmpdir}/artemis-browser-scale-it-saved.json"
})
@AutoConfigureMockMvc
class ScaleMeasurementIT
{

    static final int QUEUES = 1000;
    static final int PER_QUEUE = 20;
    static final int TOPICS = 100;
    static final int SUBSCRIPTIONS = 3;
    static final int PER_SUBSCRIPTION = 10;
    static final int PREPARED = 150;
    static final int BIG_BRANCH = 5000;

    private static GenericContainer<?> container;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SavedSearchStore savedSearches;

    @Autowired
    private SnapshotReader snapshotReader;

    @BeforeAll
    static void seed() throws Exception
    {
        assumeTrue(Boolean.getBoolean("measure"), "a measurement: run with -Dmeasure=true");
        Files.deleteIfExists(Path.of(System.getProperty("java.io.tmpdir"), "artemis-browser-scale-it-saved.json"));
        container = new GenericContainer<>(DockerImageName.parse(ArtemisBrokerSupport.IMAGE))
                .withEnv("ARTEMIS_USER", ArtemisBrokerSupport.USER)
                .withEnv("ARTEMIS_PASSWORD", ArtemisBrokerSupport.PASSWORD).withExposedPorts(61616)
                .waitingFor(Wait.forLogMessage(".*Server is now active.*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(4));
        container.start();
        long started = System.currentTimeMillis();
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url() + "&blockOnDurableSend=false"))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            connection.setClientID("scale");
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            for (int t = 0; t < TOPICS; t++)
            {
                Topic topic = session.createTopic(String.format("scale-topic-%03d", t));
                for (int s = 0; s < SUBSCRIPTIONS; s++)
                {
                    session.createDurableSubscriber(topic, "sub-" + s).close();
                }
                try (MessageProducer producer = session.createProducer(topic))
                {
                    for (int m = 0; m < PER_SUBSCRIPTION; m++)
                    {
                        producer.send(session.createTextMessage("event " + m));
                    }
                }
            }
            for (int q = 0; q < QUEUES; q++)
            {
                try (MessageProducer producer = session.createProducer(session.createQueue(queue(q))))
                {
                    producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                    for (int m = 0; m < PER_QUEUE; m++)
                    {
                        producer.send(session.createTextMessage("message " + m + " " + "x".repeat(100)));
                    }
                }
            }
            connection.close();
        }
        for (int b = 0; b < PREPARED; b++)
        {
            XaFixtures.prepare(url(), "scale-" + b, queue(b), queue(b + 1), false);
        }
        System.out.printf("SCALE seeded %d queues, %d subscriptions, %d prepared branches in %ds%n", QUEUES,
                TOPICS * SUBSCRIPTIONS, PREPARED, (System.currentTimeMillis() - started) / 1000);
    }

    @AfterAll
    static void stop()
    {
        if (container != null)
        {
            container.stop();
        }
    }

    @Test
    @DisplayName("page time, size and management calls on a large broker")
    void measurePages() throws Exception
    {
        MockHttpSession http = connect();
        BrokerSession probe = session(http);
        String someConnection = new BrokerInfoService(probe).connections().get(0).connectionId();
        List<String> pages = List.of("/overview", "/addresses", "/broker", "/connectivity", "/transactions",
                "/queues?name=" + queue(0), "/address?name=scale-topic-000",
                "/address?name=scale-topic-000&find=AMQPriority >= 0", "/client?connection=" + someConnection,
                "/diagnose", "/snapshot?format=json", "/snapshot?format=text", "/search?filter=AMQPriority = 9",
                "/search?filter=ID:00000000-0000-0000-0000-000000000000");
        measure(http, probe, pages);
    }

    @Test
    @DisplayName("Phase 14's workflows on a large broker: a real message looked up, a built filter, saved searches")
    void measureInvestigation() throws Exception
    {
        MockHttpSession http = connect();
        BrokerSession probe = session(http);
        // The last queue's first message: an exact-ID lookup reads the queues in order, so this is found last.
        String lastId = new QueueBrowseService(probe, 200, 200000, 20000, 20_000_000L)
                .page(queue(QUEUES - 1), null, 1, 1).messages().get(0).messageId();
        String everyQueue = savedSearches
                .create("scale every queue", "AMQPriority = 9", SavedSearch.Scope.ALL_QUEUES, "", false, "").id();
        String oneQueue = savedSearches
                .create("scale one queue", "AMQPriority = 9", SavedSearch.Scope.QUEUE, queue(500), false, "").id();
        String oneAddress = savedSearches
                .create("scale one address", "AMQPriority = 9", SavedSearch.Scope.ADDRESS, "scale-topic-050", false, "")
                .id();
        measure(http, probe,
                List.of("/search?filter=" + lastId, "/search?filter=AMQUserID = '" + lastId + "'",
                        "/search?build=run&priorityMin=9&priorityMax=9",
                        "/search?build=run&conditions[0].name=missing&conditions[0].type=STRING"
                                + "&conditions[0].operator=EQUALS&conditions[0].value=x",
                        "/queues?name=" + queue(500) + "&build=run&priorityMin=4&priorityMax=4", "/saved",
                        "/saved/" + everyQueue, "/saved/" + oneQueue, "/saved/" + oneAddress, "/compare"));
        String found = new String(download(http, "/search?filter=" + lastId), StandardCharsets.UTF_8);
        assertTrue(found.contains(queue(QUEUES - 1)), "the lookup did not find the last queue's message");
    }

    @Test
    @DisplayName("comparing two snapshots of the large broker: time, memory held, and no management call")
    void measureComparison() throws Exception
    {
        MockHttpSession http = connect();
        BrokerSession probe = session(http);
        byte[] earlier = download(http, "/snapshot?format=json");
        byte[] later = download(http, "/snapshot?format=json");

        long[] times = new long[3];
        long calls = 0;
        long bytes = 0;
        for (int run = 0; run < 3; run++)
        {
            long before = probe.managementCalls();
            long start = System.nanoTime();
            MvcResult compared = mockMvc.perform(multipart("/compare")
                    .file(new MockMultipartFile("earlier", "earlier.json", "application/json", earlier))
                    .file(new MockMultipartFile("later", "later.json", "application/json", later)).session(http)
                    .with(csrf()).header("Host", "localhost")).andReturn();
            times[run] = (System.nanoTime() - start) / 1_000_000;
            calls = probe.managementCalls() - before;
            assertEquals(200, compared.getResponse().getStatus());
            bytes = compared.getResponse().getContentAsByteArray().length;
        }
        Arrays.sort(times);
        System.out.printf("SCALE | `POST /compare`, two %s snapshots | %d | %s | %d |%n", size(earlier.length),
                times[1], size(bytes), calls);
        assertEquals(0, calls, "a comparison made management calls");

        // What the two read snapshots hold while they are compared: everything but the trends, which are skipped.
        long heapBefore = usedHeap();
        SnapshotFile first = snapshotReader.read("earlier", new ByteArrayInputStream(earlier), earlier.length);
        SnapshotFile second = snapshotReader.read("later", new ByteArrayInputStream(later), later.length);
        long heapAfter = usedHeap();
        System.out.printf("SCALE | two snapshots read for comparison | ~%s retained |%n", size(heapAfter - heapBefore));
        assertTrue(SnapshotComparer.compare(first, second).identity().confirmed());
    }

    private void measure(MockHttpSession http, BrokerSession probe, List<String> pages) throws Exception
    {
        System.out.println("SCALE | Page | Median ms | Response | Management calls |");
        for (String path : pages)
        {
            fetch(http, path); // warm: the session's first reading, refusals learned
            long[] times = new long[3];
            long bytes = 0;
            long calls = 0;
            for (int run = 0; run < 3; run++)
            {
                long before = probe.managementCalls();
                long start = System.nanoTime();
                bytes = fetch(http, path);
                times[run] = (System.nanoTime() - start) / 1_000_000;
                calls = probe.managementCalls() - before;
            }
            Arrays.sort(times);
            System.out.printf("SCALE | `%s` | %d | %s | %d |%n", path, times[1], size(bytes), calls);
        }
    }

    @Test
    @DisplayName("the largest transaction reply: one branch sending 5,000 messages")
    void measureOneLargeBranch() throws Exception
    {
        String url = url();
        XaFixtures.prepareMany(url, "big", queue(999), BIG_BRANCH);
        MockHttpSession http = connect();
        BrokerSession probe = session(http);
        long start = System.nanoTime();
        String reply = String.valueOf(
                probe.requireManagement().invoke(ResourceNames.BROKER, "listPreparedTransactionDetailsAsJSON"));
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("SCALE | detail reply, %d branches + one of %d messages | %dms | %s |%n", PREPARED,
                BIG_BRANCH, millis, size(reply.length()));
        Transactions read = new TransactionService(probe, 1000).collect();
        PreparedTransaction big = read.prepared().value().stream().filter(tx -> tx.globalId().equals("gtrid-big"))
                .findFirst().orElseThrow();
        assertEquals(BIG_BRANCH, big.messageTotal());
        assertEquals(TransactionService.MESSAGE_LIMIT, big.messages().size());
        XaFixtures.clear(url, "big");
    }

    @Test
    @DisplayName("a session's trend history at its cap: 240 readings of 500 followed queues")
    void measureTrendMemory() throws Exception
    {
        MockHttpSession http = connect();
        List<QueueOverview> listing = new QueueDirectory(session(http)).overview();
        assertTrue(listing.size() >= QUEUES, "listing has " + listing.size());
        long before = usedHeap();
        RateTracker tracker = new RateTracker(15, 240, 500);
        long now = System.currentTimeMillis();
        for (int reading = 0; reading < 240; reading++)
        {
            tracker.observe("scale", 1_000_000L + reading * 15_000L, listing, now + reading * 15_000L);
        }
        long after = usedHeap();
        Trends trends = tracker.trends();
        System.out.printf("SCALE | trends at cap: %d readings, %d queues followed of %d listed | ~%s retained |%n",
                trends.readings().size(), trends.byQueue().size(), listing.size(), size(after - before));
        assertEquals(240, trends.readings().size());
        assertTrue(trends.byQueue().size() <= 500);
        // Keep it reachable until measured.
        assertTrue(tracker.trends() != null);
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpSession connect() throws Exception
    {
        MockHttpSession http = new MockHttpSession();
        mockMvc.perform(post("/connect").session(http).with(csrf()).header("Host", "localhost")
                .param("host", container.getHost()).param("port", String.valueOf(container.getMappedPort(61616)))
                .param("username", ArtemisBrokerSupport.USER).param("password", ArtemisBrokerSupport.PASSWORD))
                .andReturn();
        assertTrue(session(http).isConnected());
        return http;
    }

    private static BrokerSession session(MockHttpSession http)
    {
        return (BrokerSession) http.getAttribute("scopedTarget.brokerSession");
    }

    private long fetch(MockHttpSession http, String path) throws Exception
    {
        return download(http, path).length;
    }

    private byte[] download(MockHttpSession http, String path) throws Exception
    {
        MvcResult result = mockMvc.perform(get(UriComponentsBuilder.fromUriString(path).encode().build().toUri())
                .session(http).header("Host", "localhost")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), path);
        return result.getResponse().getContentAsByteArray();
    }

    private static long usedHeap() throws InterruptedException
    {
        for (int i = 0; i < 4; i++)
        {
            System.gc();
            Thread.sleep(100);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static String size(long bytes)
    {
        return bytes >= 1_000_000 ? String.format("%.1fMB", bytes / 1e6)
                : bytes >= 1_000 ? String.format("%.0fKB", bytes / 1e3) : bytes + "B";
    }

    static String queue(int n)
    {
        return String.format("scale-q-%04d", n);
    }

    private static String url()
    {
        return "tcp://" + container.getHost() + ":" + container.getMappedPort(61616)
                + "?useTopologyForLoadBalancing=false";
    }
}
