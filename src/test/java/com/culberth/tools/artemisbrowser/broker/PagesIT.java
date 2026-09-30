package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.culberth.tools.artemisbrowser.filter.SavedSearch;
import com.culberth.tools.artemisbrowser.filter.SavedSearchStore;
import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * The whole application against a real broker: connected through its own form, every route fetched the way a browser
 * would, and nothing on the broker changed by it.
 *
 * <p>
 * {@link ReadOnlyGuaranteeIT} drives the read services; this drives the pages, so a read a controller adds on its own —
 * the queue page's transaction check, the client page's roles — is covered by construction rather than by someone
 * remembering to list it. The shared broker gets one prepared XA branch for the duration, holding a message from
 * {@link #SOURCE}, so the transaction reads have something to read; it is rolled back and forgotten afterwards.
 *
 * <p>
 * It also pins what a list page costs: the management calls behind {@code /overview} and the other list pages are the
 * same with thirty more queues on the broker. Per-resource reads belong on the pages for one resource.
 *
 * <p>
 * Phase 14's paths are in the same visit: exact message-ID lookups (one of them for the message the branch holds),
 * built filters on the search and queue pages, the saved-search pages for every scope, and {@code /compare}. Two of
 * them are held to what they promise: comparing two snapshots this broker just produced reads nothing from it, and
 * opening a saved search reads only the listing that says whether its scope is still there. Dead-letter triage
 * ({@code /triage}) is visited for every queue, plain and grouped by a property.
 */
@SpringBootTest(properties =
{ "server.address=127.0.0.1", "artemis.auth.username=", "artemis.auth.password-hash=",
        "artemis.connections-file=${java.io.tmpdir}/artemis-browser-pages-it.json",
        "artemis.saved-searches.file=${java.io.tmpdir}/artemis-browser-pages-it-saved.json"
})
@AutoConfigureMockMvc
class PagesIT
{

    static final String SOURCE = "it-pages-xa.src";
    static final String TARGET = "it-pages-xa.dst";
    private static final String BRANCH = "pages";
    private static final String FILTER = "AMQPriority >= 0";
    private static final String NOBODY = "ID:00000000-0000-0000-0000-000000000000";
    private static final String MISSING_QUEUE = "it-pages-never-created";
    private static final java.util.regex.Pattern TEMPORARY = java.util.regex.Pattern
            .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static BrokerSession probe;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SavedSearchStore savedSearches;

    private MockHttpSession http;

    @BeforeAll
    static void prepare() throws Exception
    {
        Files.deleteIfExists(Path.of(System.getProperty("java.io.tmpdir"), "artemis-browser-pages-it-saved.json"));
        ArtemisBrokerSupport.start();
        XaFixtures.prepare(ArtemisBrokerSupport.url(), BRANCH, SOURCE, TARGET, true);
        probe = ArtemisBrokerSupport.connect();
    }

    @AfterAll
    static void clear() throws Exception
    {
        if (probe != null)
        {
            probe.close();
        }
        String url = ArtemisBrokerSupport.url();
        XaFixtures.clear(url, BRANCH);
        for (String queue : List.of(SOURCE, TARGET))
        {
            remove(queue);
        }
    }

    /** The queue and its address: destroying a queue has been seen to leave an auto-created address behind. */
    private static void remove(String queue) throws Exception
    {
        String url = ArtemisBrokerSupport.url();
        XaFixtures.manage(url, "destroyQueue", queue, true, true);
        try
        {
            XaFixtures.manage(url, "deleteAddress", queue);
        }
        catch (AssertionError alreadyGone)
        {
            // Auto-deleted with its queue this time.
        }
    }

    @BeforeEach
    void connect() throws Exception
    {
        http = new MockHttpSession();
        BrokerCredentials broker = ArtemisBrokerSupport.credentials();
        MvcResult connected = mockMvc
                .perform(post("/connect").session(http).with(csrf()).header("Host", "localhost")
                        .param("host", broker.host()).param("port", String.valueOf(broker.port()))
                        .param("username", ArtemisBrokerSupport.USER).param("password", ArtemisBrokerSupport.PASSWORD))
                .andReturn();
        assertEquals(302, connected.getResponse().getStatus(), connected.getResponse().getContentAsString());
        assertTrue(session().isConnected(), "the connect form did not connect");
        if (savedSearches.all().searches().isEmpty())
        {
            save("every queue", FILTER, "ALL_QUEUES", "");
            save("the source queue", FILTER, "QUEUE", SOURCE);
            save("the source address", FILTER, "ADDRESS", SOURCE);
            save("a queue that is not there", FILTER, "QUEUE", MISSING_QUEUE);
        }
    }

    @Test
    @DisplayName("every page, three times over, moves no counter and resolves no transaction")
    void readingEveryPageChangesNothing() throws Exception
    {
        Map<String, String> before = counters();
        List<String> preparedBefore = prepared();
        assertEquals(1, preparedBefore.size(), "the fixture branch is not prepared: " + preparedBefore);

        List<String> visited = new ArrayList<>();
        for (int round = 0; round < 3; round++)
        {
            visited = visitEverything();
        }

        assertEquals(before, counters(), "a page moved a counter");
        assertEquals(preparedBefore, prepared(), "a page changed what is prepared");
        assertTrue(visited.size() > 20, "too few pages visited to mean anything: " + visited);
        assertTrue(visited.contains("/transactions"), visited.toString());
        assertTrue(visited.stream().anyMatch(path -> path.startsWith("/snapshot")), visited.toString());
        assertTrue(visited.stream().anyMatch(path -> path.startsWith("/search?filter=ID:")), visited.toString());
        assertTrue(visited.stream().anyMatch(path -> path.startsWith("/saved/")), visited.toString());
        assertTrue(visited.contains("/compare"), visited.toString());
        assertTrue(visited.stream().anyMatch(path -> path.startsWith("/triage?name=")), visited.toString());
    }

    @Test
    @DisplayName("comparing two snapshots of this broker reads nothing from it, and finds the same broker")
    void comparingSnapshotsIsOffline() throws Exception
    {
        byte[] earlier = bytes("/snapshot?format=json");
        byte[] later = bytes("/snapshot?format=json");
        Map<String, String> counters = counters();

        long before = session().managementCalls();
        MvcResult compared = mockMvc.perform(multipart("/compare")
                .file(new MockMultipartFile("earlier", "earlier.json", "application/json", earlier))
                .file(new MockMultipartFile("later", "later.json", "application/json", later)).session(http)
                .with(csrf()).header("Host", "localhost")).andReturn();
        long calls = session().managementCalls() - before;

        assertEquals(200, compared.getResponse().getStatus());
        String body = compared.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Same broker."), "two snapshots of this broker were not recognised as one broker");
        assertEquals(0, calls, "comparing two files made management calls");
        assertEquals(counters, counters());
    }

    @Test
    @DisplayName("opening a saved search reads only the listing its scope needs and runs nothing; a missing queue is explained")
    void openingASavedSearchRunsNothing() throws Exception
    {
        assertEquals(0, calls("/saved"), "the saved-search list asked the broker for something");
        List<SavedSearch> saved = savedSearches.all().searches();
        assertEquals(4, saved.size(), saved.toString());
        for (SavedSearch search : saved)
        {
            String path = "/saved/" + search.id();
            // Every queue: nothing to check. One queue: the queue listing. One address: the address listing, which
            // joins the queue listing. A search would add a browse per queue on top.
            long allowed = switch (search.scope())
            {
                case ALL_QUEUES -> 0;
                case QUEUE -> 1;
                case ADDRESS -> 2;
            };
            long cost = calls(path);
            assertTrue(cost <= allowed, path + " (" + search.scope() + ") made " + cost + " management calls");
            String body = page(path);
            if (MISSING_QUEUE.equals(search.target()))
            {
                assertTrue(body.contains("This broker has no queue named " + MISSING_QUEUE), path);
            }
        }
    }

    @Test
    @DisplayName("an exact-ID lookup finds the message the prepared branch holds, and shows what it covered")
    void lookingUpTheHeldMessage() throws Exception
    {
        TransactionMessage received = new TransactionService(probe, 100).collect().prepared().value().get(0).messages()
                .stream().filter(message -> message.operation() == TransactionMessage.Operation.RECEIVE).findFirst()
                .orElseThrow();
        String found = page("/search?filter=" + received.userId());
        assertTrue(found.contains("received in a prepared transaction"), "the held message was not seen held");
        assertTrue(found.contains("gtrid-" + BRANCH), "the branch holding it is not named");
        assertTrue(found.contains("Budgets for this lookup"), "the lookup's budgets are not shown");

        String missed = page("/search?filter=" + NOBODY);
        assertTrue(missed.contains("Budgets for this lookup"), "a lookup that saw nothing shows no coverage");
    }

    @Test
    @DisplayName("the held message's queue page, address page and transactions page all name the branch")
    void pagesLinkTheHeldMessage() throws Exception
    {
        assertTrue(page("/queues?name=" + SOURCE).contains("held by prepared XA transaction"));
        assertTrue(page("/address?name=" + SOURCE).contains("/transactions#"));
        assertTrue(page("/transactions").contains("gtrid-" + BRANCH));
        assertTrue(page("/diagnose").contains("Prepared XA transaction gtrid-" + BRANCH));
    }

    @Test
    @DisplayName("a list page costs the same number of management calls with thirty more queues on the broker")
    void listPagesDoNotScanPerResource() throws Exception
    {
        List<String> lists = List.of("/overview", "/addresses", "/broker", "/connectivity", "/transactions", "/saved",
                "/compare");
        Map<String, Long> small = new LinkedHashMap<>();
        for (String path : lists)
        {
            calls(path); // the first reading of a session can differ: rates start, refusals are learned
            small.put(path, calls(path));
        }

        List<String> extra = new ArrayList<>();
        for (int i = 0; i < 30; i++)
        {
            extra.add("it-pages-bulk-" + i);
        }
        try
        {
            seed(extra);
            Map<String, Long> large = new LinkedHashMap<>();
            for (String path : lists)
            {
                large.put(path, calls(path));
            }
            assertEquals(small, large, "a list page's cost grew with the number of queues");
        }
        finally
        {
            for (String queue : extra)
            {
                remove(queue);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Every route the app serves, for every queue, message, address and client on the broker. */
    private List<String> visitEverything() throws Exception
    {
        List<String> paths = new ArrayList<>(List.of("/", "/overview", "/broker", "/connectivity", "/transactions",
                "/addresses", "/search?filter=" + FILTER, "/search?filter=" + FILTER + "&internal=true",
                "/export?filter=" + FILTER + "&format=csv", "/export?filter=" + FILTER + "&format=json", "/diagnose",
                "/diagnose?internal=true", "/snapshot?format=json", "/snapshot?format=text", "/compare", "/saved",
                "/search?filter=" + NOBODY, "/search?filter=AMQUserID = '" + NOBODY + "'&internal=true",
                "/search?build=run&priorityMin=0&priorityMax=9&durability=DURABLE",
                "/search?build=show&conditions[0].name=orderId&conditions[0].type=STRING"
                        + "&conditions[0].operator=EQUALS&conditions[0].value=O'Brien",
                "/queues?name=" + SOURCE + "&build=run&conditions[0].name=my-prop&conditions[0].type=INTEGER"
                        + "&conditions[0].operator=GREATER_OR_EQUAL&conditions[0].value=0"));
        for (SavedSearch search : savedSearches.all().searches())
        {
            paths.add("/saved/" + search.id());
        }
        int lookups = 0;
        QueueDirectory queues = new QueueDirectory(probe);
        QueueBrowseService browse = new QueueBrowseService(probe, 200, 200000, 20000, 20_000_000L);
        for (QueueOverview queue : queues.overview())
        {
            // Temporary reply queues — the app's own, the probe's — come and go with their connections, and the app
            // does not serve its own. Everything else is visited.
            if (TEMPORARY.matcher(queue.name()).matches())
            {
                continue;
            }
            paths.add("/queues?name=" + queue.name());
            paths.add("/queues?name=" + queue.name() + "&filter=" + FILTER);
            paths.add("/export?name=" + queue.name() + "&format=json");
            paths.add("/triage?name=" + queue.name());
            paths.add("/triage?name=" + queue.name() + "&sample=3&by=_AMQ_ORIG_QUEUE");
            for (MessageSummary message : browse.page(queue.name(), null, 1, 5).messages())
            {
                // Each exact-ID lookup reads every queue, so a few real messages are enough to exercise it.
                if (lookups < 5 && message.messageId() != null && message.messageId().startsWith("ID:"))
                {
                    paths.add("/search?filter=" + message.messageId());
                    lookups++;
                }
                paths.add("/message?name=" + queue.name() + "&id=" + message.messageId());
                paths.add("/message/download?name=" + queue.name() + "&id=" + message.messageId() + "&format=txt");
            }
        }
        for (AddressOverview address : new AddressDirectory(probe, queues).overview())
        {
            paths.add("/address?name=" + address.name());
            paths.add("/address?name=" + address.name() + "&find=" + FILTER);
        }
        for (BrokerConnection connection : new BrokerInfoService(probe).connections())
        {
            paths.add("/client?connection=" + connection.connectionId());
        }
        for (String path : paths)
        {
            page(path);
        }
        return paths;
    }

    /** One page, which must be served — a 200, or the one redirect the connect page makes when connected. */
    private String page(String path) throws Exception
    {
        MvcResult result = mockMvc.perform(get(UriComponentsBuilder.fromUriString(path).encode().build().toUri())
                .session(http).header("Host", "localhost")).andReturn();
        int status = result.getResponse().getStatus();
        assertTrue(status == 200 || (path.equals("/") && status == 302),
                path + " answered " + status + ": " + result.getResponse().getErrorMessage());
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("Whitelabel Error Page"), path);
        return body;
    }

    private byte[] bytes(String path) throws Exception
    {
        MvcResult result = mockMvc.perform(get(path).session(http).header("Host", "localhost")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), path);
        return result.getResponse().getContentAsByteArray();
    }

    /** Saved through the form, as the save buttons on the search, queue and address pages do. */
    private void save(String name, String filter, String scope, String target) throws Exception
    {
        MvcResult saved = mockMvc.perform(post("/saved").session(http).with(csrf()).header("Host", "localhost")
                .param("name", name).param("filter", filter).param("scope", scope).param("target", target)).andReturn();
        assertEquals(302, saved.getResponse().getStatus());
    }

    /** The management round trips one fetch of this page costs. */
    private long calls(String path) throws Exception
    {
        long before = session().managementCalls();
        page(path);
        return session().managementCalls() - before;
    }

    /** The app's own broker session for this HTTP session: the session-scoped bean, as Spring stores it. */
    private BrokerSession session()
    {
        return (BrokerSession) http.getAttribute("scopedTarget.brokerSession");
    }

    private Map<String, String> counters()
    {
        Map<String, String> counters = new LinkedHashMap<>();
        for (QueueOverview queue : new QueueDirectory(probe).overview())
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
        return new TransactionService(probe, 100).collect().prepared().value().stream()
                .map(tx -> tx.xid() + "/" + tx.messageTotal()).toList();
    }

    private static void seed(List<String> names) throws Exception
    {
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisBrokerSupport.url()))
        {
            Connection connection = factory.createConnection(ArtemisBrokerSupport.USER, ArtemisBrokerSupport.PASSWORD);
            try
            {
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                for (String name : names)
                {
                    try (MessageProducer producer = session.createProducer(session.createQueue(name)))
                    {
                        producer.send(session.createTextMessage(name));
                    }
                }
            }
            finally
            {
                connection.close();
            }
        }
    }
}
