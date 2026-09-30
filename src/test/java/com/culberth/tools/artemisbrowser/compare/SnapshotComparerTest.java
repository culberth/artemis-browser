package com.culberth.tools.artemisbrowser.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.ClientPresence;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.Continuity;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.CounterStatus;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.FieldChange;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.FindingChange;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.FindingPresence;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.Presence;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.QueueChange;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What two snapshots of one broker can honestly say changed: never a missing value as a change, never a removed thing
 * that was only unread, and never a counter across a restart, recreation or reset as traffic.
 */
class SnapshotComparerTest
{

    private static final String T0 = "2026-09-30T08:00:00Z";
    private static final String T1 = "2026-09-30T08:40:00Z";

    /** 40 minutes apart, the broker up an hour at the first and 1h40m at the second. */
    private static SnapshotJson before()
    {
        return SnapshotJson.at(T0).uptime(3_600_000);
    }

    private static SnapshotJson after()
    {
        return SnapshotJson.at(T1).uptime(3_600_000 + 2_400_000);
    }

    private static SnapshotComparison compare(SnapshotJson before, SnapshotJson after)
    {
        return SnapshotComparer.compare(before.read(), after.read());
    }

    private static QueueChange queue(SnapshotComparison result, String name)
    {
        return result.queues().rows().stream().filter(q -> q.name().equals(name)).findFirst().orElseThrow();
    }

    private static FieldChange field(List<FieldChange> fields, String name)
    {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an unbroken run: depth is compared, counters are subtracted, and unchanged queues are only counted")
    void continuousRun()
    {
        SnapshotComparison result = compare(before().queue("orders", 7, 10, 100, 90).queue("idle", 8, 0, 5, 5),
                after().queue("orders", 7, 250, 500, 250).queue("idle", 8, 0, 5, 5));

        assertEquals(Continuity.Kind.CONTINUOUS, result.continuity().kind());
        assertTrue(result.identity().confirmed());
        assertEquals(1, result.queues().unchanged());
        QueueChange orders = queue(result, "orders");
        assertEquals(Presence.CHANGED, orders.presence());
        assertEquals("+240", field(orders.levels(), "messageCount").delta());
        assertEquals("10 → 250 messages (+240)", field(orders.levels(), "messageCount").text());
        assertEquals(CounterStatus.COUNTED, orders.counterStatus());
        assertEquals("100 → 500 messages (+400)",
                field(orders.counters(), "messagesAdded").counterText(orders.counterStatus()));
    }

    @Test
    @DisplayName("an uptime shorter than the time between them is a restart: counters are two readings, not traffic")
    void restartBetween()
    {
        // Up a minute at the first, ten at the second: never lower, yet too short for the 40 minutes between.
        SnapshotComparison result = compare(before().uptime(60_000).queue("orders", 7, 10, 100, 90),
                after().uptime(600_000).queue("orders", 7, 12, 30, 18));

        assertEquals(Continuity.Kind.RESTARTED, result.continuity().kind());
        assertTrue(result.continuity().explanation().contains("up 10m 0s at the later snapshot, but 40m 0s passed"),
                result.continuity().explanation());
        QueueChange orders = queue(result, "orders");
        assertEquals(CounterStatus.RESTART, orders.counterStatus());
        assertFalse(orders.counterStatus().traffic());
        assertEquals("100 → 30 messages (not traffic)",
                field(orders.counters(), "messagesAdded").counterText(orders.counterStatus()));
        // A level is still a level after a restart.
        assertEquals("+2", field(orders.levels(), "messageCount").delta());
    }

    @Test
    @DisplayName("an uptime lower than before is a restart even when the snapshots are close together")
    void uptimeWentBack()
    {
        SnapshotComparison result = SnapshotComparer.compare(SnapshotJson.at(T0).uptime(3_600_000).read(),
                SnapshotJson.at("2026-09-30T08:00:03Z").uptime(1_000).read());

        assertEquals(Continuity.Kind.RESTARTED, result.continuity().kind());
    }

    @Test
    @DisplayName("without uptime in ms continuity is unknown, and a difference is shown only as uncertain")
    void unknownContinuity()
    {
        SnapshotComparison result = compare(before().queue("orders", 7, 10, 100, 90),
                after().noUptime().queue("orders", 7, 10, 160, 150));

        assertEquals(Continuity.Kind.UNKNOWN, result.continuity().kind());
        QueueChange orders = queue(result, "orders");
        assertEquals(CounterStatus.UNCERTAIN, orders.counterStatus());
        assertEquals("100 → 160 messages (+60 if unbroken)",
                field(orders.counters(), "messagesAdded").counterText(orders.counterStatus()));
    }

    @Test
    @DisplayName("a changed queue id is a recreated queue, whose counters are a new queue's")
    void recreatedQueue()
    {
        SnapshotComparison result = compare(before().queue("orders", 931, 10, 100, 90),
                after().queue("orders", 940, 3, 3, 0));

        QueueChange orders = queue(result, "orders");
        assertEquals(Presence.RECREATED, orders.presence());
        assertEquals(CounterStatus.RECREATED, orders.counterStatus());
        assertEquals("id 931 then 940.", orders.counterNote());
    }

    @Test
    @DisplayName("a counter that went down with no restart seen is a reset, not negative traffic")
    void counterReset()
    {
        SnapshotComparison result = compare(before().queue("orders", 7, 10, 100, 90),
                after().queue("orders", 7, 10, 20, 10));

        QueueChange orders = queue(result, "orders");
        assertEquals(CounterStatus.RESET, orders.counterStatus());
        assertTrue(
                field(orders.counters(), "messagesAdded").counterText(CounterStatus.RESET).endsWith("(not traffic)"));
    }

    @Test
    @DisplayName("a queue in one listing and not the other is added or removed, sorted before plain changes")
    void addedAndRemoved()
    {
        SnapshotComparison result = compare(before().queue("old", 1, 4, 4, 0).queue("orders", 7, 1, 1, 0),
                after().queue("new", 2, 0, 0, 0).queue("orders", 7, 2, 2, 0));

        assertEquals(Presence.ADDED, queue(result, "new").presence());
        assertEquals(Presence.REMOVED, queue(result, "old").presence());
        assertNull(queue(result, "old").counterStatus());
        assertEquals("orders", result.queues().rows().get(2).name());
    }

    @Test
    @DisplayName("a queue section either side could not read is not compared, and nothing in it is called removed")
    void unreadIsNotRemoved()
    {
        SnapshotComparison result = compare(before().queue("orders", 7, 10, 100, 90), after().denied("queues"));

        assertFalse(result.queues().compared());
        assertTrue(result.queues().rows().isEmpty());
        assertTrue(result.queues().why().contains("the later snapshot could not read it (not permitted for this user"),
                result.queues().why());
    }

    @Test
    @DisplayName("a configuration change is shown; a setting one side could not read is shown apart, not as a change")
    void configuration()
    {
        SnapshotComparison result = compare(before().queue("orders", 7, 1, 1, 0).queue("ring", 8, 1, 1, 0),
                after().queue("orders", 7, 1, 1, 0).queueConfig("orders", "ringSize", 3).queue("ring", 8, 1, 1, 0)
                        .queueConfig("ring", "ringSize", SnapshotJson.unavailable("UNSUPPORTED", "not available")));

        var orders = queue(result, "orders").configuration().get(0);
        assertEquals("ringSize", orders.key());
        assertTrue(orders.comparable());
        assertEquals("-1 → 3", orders.before().display() + " → " + orders.after().display());
        var ring = queue(result, "ring").configuration().get(0);
        assertFalse(ring.comparable());
    }

    @Test
    @DisplayName("a health value one side could not read is never a change")
    void missingHealthIsNotAChange()
    {
        SnapshotJson later = after();
        later.health().set("connectionCount", SnapshotJson.unavailable("DENIED", "not permitted for this user"));

        FieldChange connections = field(compare(before(), later).health(), "connectionCount");

        assertFalse(connections.changed());
        assertTrue(connections.oneSided());
        assertNull(connections.delta());
        assertEquals("3 connections → — not permitted for this user", connections.text());
    }

    @Test
    @DisplayName("two different node ids are two brokers, and are refused")
    void differentBrokers()
    {
        SnapshotRejected e = assertThrows(SnapshotRejected.class, () -> compare(before(), after().node("node-2")));
        assertTrue(e.getMessage().contains("different brokers"), e.getMessage());
    }

    @Test
    @DisplayName("with a node id missing, only the same address lets the comparison go ahead, marked unconfirmed")
    void identityByAddressOnly()
    {
        SnapshotComparison result = compare(before(), after().nodeDenied());
        assertFalse(result.identity().confirmed());
        assertTrue(result.identity().notes().get(0).contains("identified by address only"));

        SnapshotRejected e = assertThrows(SnapshotRejected.class,
                () -> compare(before(), after().nodeDenied().host("elsewhere")));
        assertTrue(e.getMessage().contains("cannot be confirmed to be the same broker"), e.getMessage());
    }

    @Test
    @DisplayName("the same node id at another address is compared, with a note")
    void sameNodeOtherAddress()
    {
        SnapshotComparison result = compare(before(), after().host("backup-host"));
        assertTrue(result.identity().confirmed());
        assertTrue(result.identity().notes().get(0).contains("backup that took over"));
    }

    @Test
    @DisplayName("different schema versions are refused")
    void differentSchemas()
    {
        SnapshotFile first = before().read();
        SnapshotFile second = after().read();
        SnapshotFile future = new SnapshotFile(second.label(), 2, second.startedAt(), second.finishedAt(),
                second.host(), second.port(), second.healthCollected(), second.health(), second.queues(),
                second.addresses(), second.addressSettings(), second.consumers(), second.connections(),
                second.diagnosis(), second.unavailable(), second.omitted(), second.limits());

        SnapshotRejected e = assertThrows(SnapshotRejected.class, () -> SnapshotComparer.compare(first, future));
        assertTrue(e.getMessage().contains("different snapshot schemas (1 and 2)"), e.getMessage());
    }

    @Test
    @DisplayName("given latest first, the files are put in time order and it says so")
    void reorders()
    {
        SnapshotComparison result = SnapshotComparer.compare(after().read(), before().read());
        assertTrue(result.reordered());
        assertEquals(2_400_000, result.elapsedMillis());
        assertEquals("40m 0s", result.elapsedText());
    }

    @Test
    @DisplayName("consumers and connections come and go; one missing from a cut-short listing is unknown, not gone")
    void clients()
    {
        SnapshotComparison result = compare(
                before().consumer("c1", "orders", 0).consumer("c2", "orders", 3).connection("c1", "10.0.0.1")
                        .connection("c2", "10.0.0.2"),
                after().consumer("c1", "orders", 5).consumer("c3", "orders", 0).connection("c1", "10.0.0.1")
                        .connection("c3", "10.0.0.3"));

        var consumers = result.consumers().rows();
        assertEquals(List.of(ClientPresence.APPEARED, ClientPresence.GONE, ClientPresence.CHANGED),
                consumers.stream().map(c -> c.presence()).toList());
        assertEquals("0 → 5 messages (+5)", consumers.get(2).level().text());
        assertEquals(1, result.connections().unchanged());

        SnapshotComparison cut = compare(before().connection("c1", "a").connection("c2", "b"),
                after().connection("c1", "a").truncated("connections", 2));
        assertEquals(ClientPresence.NOT_IN_KEPT_ROWS, cut.connections().rows().get(0).presence());
        assertTrue(cut.connections().notes().get(0).contains("cut short"));
    }

    @Test
    @DisplayName("findings are new, no longer reported or changed, matched across their counts")
    void findings()
    {
        SnapshotComparison result = compare(
                before().finding("watch", "'orders' is holding 7 message(s)", "orders")
                        .finding("stuck", "'q1' is paused", "q1").finding("watch", "'same' has no queues", null),
                after().finding("stuck", "'orders' is holding 900 message(s)", "orders")
                        .finding("stuck", "Nothing is reading 'q2'", "q2")
                        .finding("watch", "'same' has no queues", null)
                        .couldNotCheck("Disk use against its limit: not permitted"));

        var rows = result.findings().rows();
        assertEquals(List.of(FindingPresence.NEW, FindingPresence.RESOLVED, FindingPresence.CHANGED),
                rows.stream().map(FindingChange::presence).toList());
        assertEquals("watch", rows.get(2).before().severity());
        assertEquals("stuck", rows.get(2).after().severity());
        assertEquals(1, result.findings().unchanged());
        assertTrue(result.findings().notes().get(0).contains("may not have been looked for"));
    }

    @Test
    @DisplayName("counts are blanked outside quoted names only, so two numbered queues stay two findings")
    void findingIdentity()
    {
        assertEquals("'orders-1' is holding # message(s)",
                SnapshotFile.FindingRow.withoutCounts("'orders-1' is holding 12 message(s)"));
        assertFalse(new SnapshotFile.FindingRow("s", "", "'q1' is paused", "", null, null, null, null).identity()
                .equals(new SnapshotFile.FindingRow("s", "", "'q2' is paused", "", null, null, null, null).identity()));
    }

    @Test
    @DisplayName("a diagnosis either side could not make is not compared")
    void findingsUnread()
    {
        SnapshotComparison result = compare(before().finding("stuck", "'q1' is paused", "q1"),
                after().denied("diagnosis"));
        assertFalse(result.findings().compared());
    }

    @Test
    @DisplayName("address settings: changes shown, masked values and one-sided addresses noted, unread ones explained")
    void addressSettings()
    {
        SnapshotComparison result = compare(
                before().setting("orders", "maxSizeBytes", "-1").setting("orders", "bridgePassword", "[redacted]")
                        .setting("only-before", "x", "1").setting("locked", "a", "1"),
                after().setting("orders", "maxSizeBytes", "10485760").setting("orders", "bridgePassword", "[redacted]")
                        .setting("orders", "pageLimitMessages", "30").settingsDenied("locked"));

        var rows = result.settings().rows();
        var locked = rows.stream().filter(r -> r.address().equals("locked")).findFirst().orElseThrow();
        assertTrue(locked.note().startsWith("Not read in the later snapshot: not permitted"), locked.note());
        var orders = rows.stream().filter(r -> r.address().equals("orders")).findFirst().orElseThrow();
        assertEquals(List.of("maxSizeBytes", "pageLimitMessages"),
                orders.changes().stream().map(c -> c.key()).toList());
        assertEquals("not reported (the broker's default)", orders.changes().get(1).before().missing());
        assertTrue(
                result.settings().notes().stream().anyMatch(n -> n.startsWith("1 address(es) had settings read in")));
        assertTrue(result.settings().notes().stream().anyMatch(n -> n.startsWith("1 secret-looking value")));
    }

    @Test
    @DisplayName("addresses are added, removed and changed by level")
    void addresses()
    {
        SnapshotComparison result = compare(before().address("a", 100).address("gone", 0),
                after().address("a", 5_000).address("new", 0));

        assertEquals(List.of(Presence.REMOVED, Presence.ADDED, Presence.CHANGED),
                result.addresses().rows().stream().map(a -> a.presence()).toList());
        assertEquals("+4900", field(result.addresses().rows().get(2).levels(), "addressSizeBytes").delta());
    }
}
