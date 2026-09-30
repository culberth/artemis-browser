package com.culberth.tools.artemisbrowser.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedSearchStoreTest
{

    @TempDir
    Path dir;

    private SavedSearchStore store(int maxEntries)
    {
        return new SavedSearchStore(dir.resolve("sub").resolve("saved.json").toString(), maxEntries, 20, 100,
                Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("no file yet is an empty, writable list, and the first save creates it")
    void startsEmpty() throws Exception
    {
        SavedSearchStore store = store(10);
        assertTrue(store.all().searches().isEmpty());
        assertFalse(store.all().hasProblem());

        SavedSearch saved = store.create(" stuck EU ", "region = 'eu'", SavedSearch.Scope.QUEUE, "orders", false,
                "localhost:61616");
        assertEquals("stuck EU", saved.name());
        assertEquals(Instant.parse("2026-09-30T12:00:00Z"), saved.created());
        assertTrue(Files.isRegularFile(store.file()));
        assertEquals(saved, store.find(saved.id()));
    }

    @Test
    @DisplayName("what is written is the search and nothing else: no user, no password, no results")
    void writesOnlyTheSearch() throws Exception
    {
        SavedSearchStore store = store(10);
        store.create("one", "AMQPriority = 9", SavedSearch.Scope.ALL_QUEUES, "ignored", true, "broker:61616");
        String json = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(json.contains("AMQPriority = 9"), json);
        assertEquals("", store.all().searches().get(0).target());
        assertFalse(json.toLowerCase().contains("password"), json);
        assertFalse(json.toLowerCase().contains("user"), json);
    }

    @Test
    @DisplayName("rename and delete change only the one entry")
    void renameAndDelete()
    {
        SavedSearchStore store = store(10);
        SavedSearch a = store.create("a", "x = 1", SavedSearch.Scope.ALL_QUEUES, "", false, "");
        SavedSearch b = store.create("b", "x = 2", SavedSearch.Scope.ADDRESS, "events", false, "");

        SavedSearch renamed = store.rename(a.id(), "alpha");
        assertEquals("alpha", renamed.name());
        assertEquals(a.created(), renamed.created());
        assertEquals("x = 1", store.find(a.id()).filter());

        assertTrue(store.delete(b.id()));
        assertFalse(store.delete(b.id()));
        assertNull(store.find(b.id()));
        assertEquals(1, store.all().searches().size());
        assertThrows(SavedSearchException.class, () -> store.rename(b.id(), "gone"));
    }

    @Test
    @DisplayName("names are required, bounded, free of control characters, and unique regardless of case")
    void names()
    {
        SavedSearchStore store = store(10);
        store.create("Orders", "x = 1", SavedSearch.Scope.ALL_QUEUES, "", false, "");
        assertTrue(assertThrows(SavedSearchException.class,
                () -> store.create("orders", "x = 2", SavedSearch.Scope.ALL_QUEUES, "", false, "")).getMessage()
                .contains("already"));
        assertThrows(SavedSearchException.class,
                () -> store.create("  ", "x = 2", SavedSearch.Scope.ALL_QUEUES, "", false, ""));
        assertThrows(SavedSearchException.class,
                () -> store.create("x".repeat(21), "x = 2", SavedSearch.Scope.ALL_QUEUES, "", false, ""));
        assertThrows(SavedSearchException.class,
                () -> store.create("bad\nname", "x = 2", SavedSearch.Scope.ALL_QUEUES, "", false, ""));
        assertEquals(1, store.all().searches().size());
    }

    @Test
    @DisplayName("a filter is required and bounded; a queue or address scope needs its name")
    void filtersAndScopes()
    {
        SavedSearchStore store = store(10);
        assertThrows(SavedSearchException.class,
                () -> store.create("a", " ", SavedSearch.Scope.ALL_QUEUES, "", false, ""));
        assertThrows(SavedSearchException.class,
                () -> store.create("a", "x".repeat(101), SavedSearch.Scope.ALL_QUEUES, "", false, ""));
        assertThrows(SavedSearchException.class,
                () -> store.create("a", "x = 1", SavedSearch.Scope.QUEUE, "", false, ""));
        assertThrows(SavedSearchException.class, () -> store.create("a", "x = 1", null, "", false, ""));
        assertTrue(store.all().searches().isEmpty());
    }

    @Test
    @DisplayName("at most max-entries searches")
    void bounded()
    {
        SavedSearchStore store = store(2);
        store.create("a", "x = 1", SavedSearch.Scope.ALL_QUEUES, "", false, "");
        store.create("b", "x = 1", SavedSearch.Scope.ALL_QUEUES, "", false, "");
        assertTrue(assertThrows(SavedSearchException.class,
                () -> store.create("c", "x = 1", SavedSearch.Scope.ALL_QUEUES, "", false, "")).getMessage()
                .contains("Delete one first"));
    }

    @Test
    @DisplayName("a file that cannot be read is reported and never overwritten")
    void corruptFileIsKept() throws Exception
    {
        SavedSearchStore store = store(10);
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), "{ not json", StandardCharsets.UTF_8);

        SavedSearchStore.Listing listing = store.all();
        assertTrue(listing.hasProblem());
        assertFalse(listing.writable());
        assertTrue(listing.problem().contains("will not be overwritten"), listing.problem());
        assertThrows(SavedSearchException.class,
                () -> store.create("a", "x = 1", SavedSearch.Scope.ALL_QUEUES, "", false, ""));
        assertEquals("{ not json", Files.readString(store.file(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a file far larger than the limits allow is not read")
    void oversizedFile() throws Exception
    {
        SavedSearchStore store = store(1);
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), "[" + " ".repeat(20_000) + "]", StandardCharsets.UTF_8);
        assertFalse(store.all().writable());
        assertTrue(store.all().problem().contains("was not read"), store.all().problem());
    }

    @Test
    @DisplayName("unusable entries are left out, said so, and removed by the next write")
    void unusableEntries() throws Exception
    {
        SavedSearchStore store = store(10);
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), """
            [
              {"id":"1","name":"good","filter":"x = 1","scope":"ALL_QUEUES","target":"","internal":false,
               "savedFrom":"","created":"2026-09-01T00:00:00Z","updated":"2026-09-01T00:00:00Z","extra":"ignored"},
              {"id":"2","name":"","filter":"x = 1","scope":"ALL_QUEUES"},
              {"id":"3","name":"no scope target","filter":"x = 1","scope":"QUEUE","target":""}
            ]
            """, StandardCharsets.UTF_8);

        SavedSearchStore.Listing listing = store.all();
        assertEquals(1, listing.searches().size());
        assertEquals("good", listing.searches().get(0).name());
        assertTrue(listing.writable());
        assertTrue(listing.problem().contains("2 entries"), listing.problem());

        store.rename("1", "better");
        assertFalse(store.all().hasProblem());
        assertEquals("better", store.find("1").name());
    }

    @Test
    @DisplayName("saved searches read back sorted by name")
    void sorted()
    {
        SavedSearchStore store = store(10);
        store.create("beta", "x = 1", SavedSearch.Scope.ALL_QUEUES, "", false, "");
        store.create("Alpha", "x = 1", SavedSearch.Scope.ALL_QUEUES, "", false, "");
        assertEquals("Alpha", store.all().searches().get(0).name());
        assertNotNull(store.all().searches().get(1).updated());
    }
}
