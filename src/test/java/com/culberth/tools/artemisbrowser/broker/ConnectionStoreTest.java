package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionStoreTest
{

    @TempDir
    Path temp;

    @Test
    @DisplayName("saves and reads back a connection")
    void roundTrips()
    {
        ConnectionStore store = store();
        store.save(new SavedConnection("Local", "localhost", 61616, "artemis"));

        List<SavedConnection> all = store.all();

        assertEquals(1, all.size());
        assertEquals("artemis@localhost:61616", all.get(0).key());
        assertEquals("Local", all.get(0).label());
    }

    @Test
    @DisplayName("the file on disk never contains a password field")
    void fileHoldsNoPassword() throws Exception
    {
        ConnectionStore store = store();
        store.save(new SavedConnection("Local", "localhost", 61616, "artemis"));

        String written = Files.readString(file());

        // The record has no password component, so this guards against anyone adding one later
        // without noticing that it would then be persisted in clear text.
        assertFalse(written.toLowerCase().contains("password"), written);
    }

    @Test
    @DisplayName("saving the same broker and user twice replaces rather than duplicates")
    void saveIsIdempotentPerKey()
    {
        ConnectionStore store = store();
        store.save(new SavedConnection("First", "localhost", 61616, "artemis"));
        store.save(new SavedConnection("Second", "localhost", 61616, "artemis"));

        assertEquals(1, store.all().size());
        assertEquals("Second", store.all().get(0).label());
    }

    @Test
    @DisplayName("a different user on the same broker is a separate entry")
    void differentUserIsSeparateEntry()
    {
        ConnectionStore store = store();
        store.save(new SavedConnection("A", "localhost", 61616, "artemis"));
        store.save(new SavedConnection("B", "localhost", 61616, "admin"));

        assertEquals(2, store.all().size());
    }

    @Test
    @DisplayName("forgetting removes only the named entry")
    void removesOne()
    {
        ConnectionStore store = store();
        store.save(new SavedConnection("A", "localhost", 61616, "artemis"));
        store.save(new SavedConnection("B", "other", 61616, "artemis"));

        store.remove("artemis@localhost:61616");

        assertEquals(1, store.all().size());
        assertNull(store.find("artemis@localhost:61616"));
    }

    @Test
    @DisplayName("a missing file reads as empty rather than failing")
    void missingFileIsEmpty()
    {
        assertTrue(store().all().isEmpty());
    }

    @Test
    @DisplayName("a corrupt file reads as empty rather than blocking the connect page")
    void corruptFileIsEmpty() throws Exception
    {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{ this is not json");

        // Being unable to remember a hostname must never stop someone reaching their broker.
        assertTrue(store().all().isEmpty());
    }

    private ConnectionStore store()
    {
        return new ConnectionStore(file().toString());
    }

    private Path file()
    {
        return temp.resolve("nested").resolve("connections.json");
    }
}
