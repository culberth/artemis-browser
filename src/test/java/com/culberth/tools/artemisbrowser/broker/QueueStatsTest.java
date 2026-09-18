package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link QueueStats#browseName()} is the one piece of Artemis addressing this app cannot get wrong
 * without the symptom being "that queue is always empty" rather than an error.
 */
class QueueStatsTest {

    @Test
    @DisplayName("a queue whose name matches its address browses under its plain name")
    void plainNameWhenAddressMatches() {
        assertEquals("orders", stats("orders", "orders").browseName());
    }

    @Test
    @DisplayName("a queue bound under a different address needs its FQQN")
    void fqqnWhenAddressDiffers() {
        // A multicast subscription: queue 'subscriber-1' on address 'events'. Browsing the bare
        // name resolves against addresses first and finds nothing, so it must be qualified.
        assertEquals("events::subscriber-1", stats("subscriber-1", "events").browseName());
    }

    @Test
    @DisplayName("a null address falls back to the plain name rather than producing 'null::name'")
    void nullAddressFallsBack() {
        assertEquals("orders", stats("orders", null).browseName());
    }

    private QueueStats stats(String name, String address) {
        return new QueueStats(name, address, "ANYCAST", 0, 0, 0, 0, 0, 0, true, false);
    }
}
