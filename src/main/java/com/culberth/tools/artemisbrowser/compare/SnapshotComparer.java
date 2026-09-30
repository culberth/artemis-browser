package com.culberth.tools.artemisbrowser.compare;

import com.culberth.tools.artemisbrowser.broker.Redaction;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.AddressChange;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.ClientChange;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.ClientPresence;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.Continuity;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.CounterStatus;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.FieldChange;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.FindingChange;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.FindingPresence;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.Identity;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.Part;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.Presence;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.QueueChange;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.SettingChange;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparison.SettingsChange;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Compares two {@link SnapshotFile}s of one broker. Pure: no broker, no session, no clock — two files in, one
 * {@link SnapshotComparison} out, or a {@link SnapshotRejected} saying why they cannot be compared.
 *
 * <p>
 * The rules that keep a comparison honest:
 * <ul>
 * <li><b>Same broker, or nothing.</b> Two known node ids that differ are refused. With a node id missing on either side
 * only a matching address lets the comparison go ahead, and it says the identity is unconfirmed.</li>
 * <li><b>Missing is not removed.</b> A queue, address or client is removed only when its section was read on both sides
 * and it is in one and not the other. A section either side could not read is not compared, with the reason; a listing
 * cut short says a row may be past the rows kept.</li>
 * <li><b>Levels compare; counters subtract only across one unbroken run.</b> Depth, in flight and consumers are levels,
 * comparable across any gap. Added, acknowledged, expired and killed are running totals, subtracted only when the
 * broker's uptime shows it did not restart, the queue's id did not change and no counter went down. Otherwise both
 * readings are shown and the difference is labelled as not traffic, or as uncertain when no restart can be ruled
 * out.</li>
 * </ul>
 *
 * <p>
 * A restart is read from {@code uptimeMillis}: an uptime at the later snapshot shorter than the time between them (less
 * {@value #UPTIME_TOLERANCE_MILLIS}ms for the reads themselves), or shorter than at the earlier one. Older snapshots
 * without it leave continuity unknown.
 */
public final class SnapshotComparer
{

    static final long UPTIME_TOLERANCE_MILLIS = 5_000;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private static final Map<String, String> HEALTH_UNITS = new LinkedHashMap<>();

    static
    {
        HEALTH_UNITS.put("version", "");
        HEALTH_UNITS.put("state", "");
        HEALTH_UNITS.put("nodeId", "");
        HEALTH_UNITS.put("uptime", "");
        HEALTH_UNITS.put("uptimeMillis", "ms");
        HEALTH_UNITS.put("connectionCount", "connections");
        HEALTH_UNITS.put("sessionCount", "sessions");
        HEALTH_UNITS.put("consumerCount", "consumers");
        HEALTH_UNITS.put("addressMemoryUsedBytes", "bytes");
        HEALTH_UNITS.put("addressMemoryUsedPercent", "%");
        HEALTH_UNITS.put("globalMaxSizeBytes", "bytes");
        HEALTH_UNITS.put("diskStoreUsedPercent", "%");
        HEALTH_UNITS.put("maxDiskUsagePercent", "%");
    }

    private static final Map<String, String> QUEUE_UNITS = Map.of("messageCount", "messages", "deliveringCount",
            "messages", "scheduledCount", "messages", "consumerCount", "consumers", "paused", "", "messagesAdded",
            "messages", "messagesAcknowledged", "messages", "messagesExpired", "messages", "messagesKilled",
            "messages");

    private static final Map<String, String> ADDRESS_UNITS = Map.of("messageCount", "messages", "addressSizeBytes",
            "bytes", "pages", "pages");

    private SnapshotComparer()
    {
    }

    /** The two files in either order; the earlier collection becomes "before". */
    public static SnapshotComparison compare(SnapshotFile first, SnapshotFile second)
    {
        boolean reordered = second.startedAt().isBefore(first.startedAt());
        SnapshotFile before = reordered ? second : first;
        SnapshotFile after = reordered ? first : second;

        if (before.schemaVersion() != after.schemaVersion())
        {
            throw new SnapshotRejected("The files use different snapshot schemas (" + before.schemaVersion() + " and "
                    + after.schemaVersion() + "), so the same field may not mean the same thing in both.");
        }
        Identity identity = identity(before, after);
        Continuity continuity = continuity(before, after);
        long elapsed = after.startedAt().toEpochMilli() - before.startedAt().toEpochMilli();

        return new SnapshotComparison(before, after, reordered, elapsed, identity, continuity, health(before, after),
                queues(before, after, continuity), addresses(before, after), settings(before, after),
                consumers(before, after), connections(before, after), findings(before, after));
    }

    // ---------------------------------------------------------------- identity and continuity

    static Identity identity(SnapshotFile before, SnapshotFile after)
    {
        Value b = before.nodeId();
        Value a = after.nodeId();
        boolean sameAddress = !before.host().isBlank() && before.address().equals(after.address());
        List<String> notes = new ArrayList<>();
        if (b.known() && a.known())
        {
            if (!b.text().equals(a.text()))
            {
                throw new SnapshotRejected("These are snapshots of different brokers: node " + b.text() + " ("
                        + before.address() + ") and node " + a.text() + " (" + after.address()
                        + "). Snapshots are compared only from the same broker over time.");
            }
            if (!sameAddress)
            {
                notes.add("Reached at " + before.address() + " and then at " + after.address()
                        + " with the same node id: a different route to it, or a backup that took over.");
            }
            return new Identity("The same node id, " + a.text() + ", in both.", true, notes);
        }
        if (!sameAddress)
        {
            throw new SnapshotRejected("These cannot be confirmed to be the same broker: the node id is missing from "
                    + missingIn(b, a) + " (" + (b.known() ? a.display() : b.display()) + "), and the addresses differ ("
                    + before.address() + ", " + after.address() + ").");
        }
        notes.add("The node id is missing from " + missingIn(b, a) + " (" + (b.known() ? a.display() : b.display())
                + "), so the broker is identified by address only. A different broker at the same address would look"
                + " the same.");
        return new Identity("The same address, " + after.address() + ".", false, notes);
    }

    private static String missingIn(Value before, Value after)
    {
        return !before.known() && !after.known() ? "both snapshots"
                : !before.known() ? "the earlier snapshot" : "the later snapshot";
    }

    static Continuity continuity(SnapshotFile before, SnapshotFile after)
    {
        Value later = after.uptimeMillis();
        Value earlier = before.uptimeMillis();
        long between = after.healthTime().toEpochMilli() - before.healthTime().toEpochMilli();
        if (!later.isNumber())
        {
            return new Continuity(Continuity.Kind.UNKNOWN,
                    "The later snapshot has no uptime in milliseconds (" + later.display()
                            + "), so a restart between them cannot be ruled out. Snapshots from before"
                            + " uptimeMillis was recorded always compare this way.");
        }
        long up = later.number();
        if (earlier.isNumber() && up < earlier.number())
        {
            return new Continuity(Continuity.Kind.RESTARTED,
                    "The broker restarted between them: up " + SnapshotComparison.durationText(earlier.number())
                            + " at the earlier snapshot and " + SnapshotComparison.durationText(up)
                            + " at the later one.");
        }
        if (up + UPTIME_TOLERANCE_MILLIS < between)
        {
            return new Continuity(Continuity.Kind.RESTARTED,
                    "The broker restarted between them: up " + SnapshotComparison.durationText(up)
                            + " at the later snapshot, but " + SnapshotComparison.durationText(between)
                            + " passed between the two.");
        }
        return new Continuity(Continuity.Kind.CONTINUOUS,
                "No restart between them: up " + SnapshotComparison.durationText(up) + " at the later snapshot, "
                        + SnapshotComparison.durationText(between) + " after the earlier one.");
    }

    // ---------------------------------------------------------------- broker

    private static List<FieldChange> health(SnapshotFile before, SnapshotFile after)
    {
        Set<String> names = new LinkedHashSet<>(HEALTH_UNITS.keySet());
        names.addAll(before.health().keySet());
        names.addAll(after.health().keySet());
        List<FieldChange> rows = new ArrayList<>();
        for (String name : names)
        {
            if (!before.health().containsKey(name) && !after.health().containsKey(name))
            {
                continue;
            }
            rows.add(FieldChange.of(name, HEALTH_UNITS.getOrDefault(name, ""), get(before.health(), name),
                    get(after.health(), name)));
        }
        return rows;
    }

    // ---------------------------------------------------------------- queues

    private static Part<QueueChange> queues(SnapshotFile before, SnapshotFile after, Continuity continuity)
    {
        var b = before.queues();
        var a = after.queues();
        String evidence = evidence("queue listing", b.collectedAt(), a.collectedAt());
        String why = notRead(b, a);
        if (why != null)
        {
            return Part.notCompared(why, evidence);
        }
        List<QueueChange> rows = new ArrayList<>();
        int unchanged = 0;
        for (String name : union(b.data().keySet(), a.data().keySet()))
        {
            SnapshotFile.QueueRow was = b.data().get(name);
            SnapshotFile.QueueRow is = a.data().get(name);
            if (was == null || is == null)
            {
                SnapshotFile.QueueRow only = was == null ? is : was;
                rows.add(new QueueChange(name, only.address(), only.internal(),
                        was == null ? Presence.ADDED : Presence.REMOVED, oneSide(only.levels(), was == null), null, "",
                        List.of(), List.of(), evidence));
                continue;
            }
            QueueChange change = queue(was, is, continuity, evidence);
            if (change == null)
            {
                unchanged++;
            }
            else
            {
                rows.add(change);
            }
        }
        rows.sort(Comparator.comparing((QueueChange q) -> q.presence() == Presence.CHANGED)
                .thenComparing(Comparator.comparingLong(QueueChange::depthChange).reversed())
                .thenComparing(QueueChange::name));
        return new Part<>(true, null, evidence, rows, unchanged, List.of());
    }

    /** One queue in both; null when nothing about it differs. */
    private static QueueChange queue(SnapshotFile.QueueRow was, SnapshotFile.QueueRow is, Continuity continuity,
            String evidence)
    {
        List<FieldChange> levels = fields(was.levels(), is.levels(), QUEUE_UNITS);
        List<FieldChange> counters = fields(was.counters(), is.counters(), QUEUE_UNITS);
        boolean recreated = was.id().known() && is.id().known() && !was.id().text().equals(is.id().text());
        boolean wentDown = counters.stream().anyMatch(c -> c.delta() != null && c.delta().startsWith("-"));

        CounterStatus status;
        String note = "";
        if (continuity.restarted())
        {
            status = CounterStatus.RESTART;
        }
        else if (recreated)
        {
            status = CounterStatus.RECREATED;
            note = "id " + was.id().text() + " then " + is.id().text() + ".";
        }
        else if (wentDown)
        {
            status = CounterStatus.RESET;
        }
        else if (!continuity.continuous())
        {
            status = CounterStatus.UNCERTAIN;
        }
        else if (!was.id().known() || !is.id().known())
        {
            status = CounterStatus.UNCERTAIN;
            note = "The queue id is missing from one side, so a recreation cannot be ruled out.";
        }
        else
        {
            status = CounterStatus.COUNTED;
        }

        List<SettingChange> configuration = settingChanges(was.configuration(), is.configuration());
        boolean changed = recreated || levels.stream().anyMatch(FieldChange::changed)
                || counters.stream().anyMatch(FieldChange::changed) || !configuration.isEmpty();
        if (!changed)
        {
            return null;
        }
        return new QueueChange(is.name(), is.address(), is.internal(),
                recreated ? Presence.RECREATED : Presence.CHANGED, levels, status, note, counters, configuration,
                evidence);
    }

    /** The values of a thing that exists on one side only, against a blank on the other. */
    private static List<FieldChange> oneSide(Map<String, Value> values, boolean added)
    {
        List<FieldChange> rows = new ArrayList<>();
        Value none = Value.missing(added ? "not there yet" : "no longer there", "");
        values.forEach((name, value) -> rows
                .add(new FieldChange(name, QUEUE_UNITS.getOrDefault(name, ADDRESS_UNITS.getOrDefault(name, "")),
                        added ? none : value, added ? value : none, false, null)));
        return rows;
    }

    // ---------------------------------------------------------------- addresses

    private static Part<AddressChange> addresses(SnapshotFile before, SnapshotFile after)
    {
        var b = before.addresses();
        var a = after.addresses();
        String evidence = evidence("address listing", b.collectedAt(), a.collectedAt());
        String why = notRead(b, a);
        if (why != null)
        {
            return Part.notCompared(why, evidence);
        }
        List<AddressChange> rows = new ArrayList<>();
        int unchanged = 0;
        for (String name : union(b.data().keySet(), a.data().keySet()))
        {
            SnapshotFile.AddressRow was = b.data().get(name);
            SnapshotFile.AddressRow is = a.data().get(name);
            if (was == null || is == null)
            {
                SnapshotFile.AddressRow only = was == null ? is : was;
                rows.add(new AddressChange(name, only.internal(), was == null ? Presence.ADDED : Presence.REMOVED,
                        oneSide(only.levels(), was == null), evidence));
                continue;
            }
            List<FieldChange> levels = fields(was.levels(), is.levels(), ADDRESS_UNITS);
            if (levels.stream().anyMatch(FieldChange::changed))
            {
                rows.add(new AddressChange(name, is.internal(), Presence.CHANGED, levels, evidence));
            }
            else
            {
                unchanged++;
            }
        }
        rows.sort(Comparator.comparing((AddressChange c) -> c.presence() == Presence.CHANGED)
                .thenComparing(AddressChange::name));
        return new Part<>(true, null, evidence, rows, unchanged, List.of());
    }

    private static Part<SettingsChange> settings(SnapshotFile before, SnapshotFile after)
    {
        String evidence = "address settings, collected " + window(before) + " → " + window(after) + " UTC";
        var b = before.addressSettings();
        var a = after.addressSettings();
        List<SettingsChange> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int unchanged = 0;
        int oneSided = 0;
        int masked = 0;
        for (String address : union(b.keySet(), a.keySet()))
        {
            var was = b.get(address);
            var is = a.get(address);
            if (was == null || is == null)
            {
                oneSided++;
                continue;
            }
            if (!was.available() || !is.available())
            {
                var missing = was.available() ? is : was;
                rows.add(new SettingsChange(
                        address, List.of(), "Not read in the " + (was.available() ? "later" : "earlier") + " snapshot: "
                                + missing.why() + (missing.detail().isBlank() ? "" : " — " + missing.detail()),
                        evidence));
                continue;
            }
            List<SettingChange> changes = new ArrayList<>();
            for (String key : union(was.data().keySet(), is.data().keySet()))
            {
                Value before1 = was.data().getOrDefault(key, Value.missing("not reported (the broker's default)", ""));
                Value after1 = is.data().getOrDefault(key, Value.missing("not reported (the broker's default)", ""));
                if (Redaction.MASK.equals(before1.text()) && Redaction.MASK.equals(after1.text()))
                {
                    masked++;
                    continue;
                }
                boolean absentOnOneSide = !was.data().containsKey(key) || !is.data().containsKey(key);
                if (absentOnOneSide || before1.differsFrom(after1))
                {
                    changes.add(new SettingChange(key, before1, after1, true));
                }
                else if (before1.known() != after1.known())
                {
                    changes.add(new SettingChange(key, before1, after1, false));
                }
            }
            if (changes.isEmpty())
            {
                unchanged++;
            }
            else
            {
                rows.add(new SettingsChange(address, changes, "", evidence));
            }
        }
        if (oneSided > 0)
        {
            notes.add(oneSided + " address(es) had settings read in only one snapshot and are not compared. A snapshot"
                    + " reads settings for a bounded set of addresses, those with something to explain first, so the"
                    + " set differs between snapshots.");
        }
        if (masked > 0)
        {
            notes.add(masked + " secret-looking value(s) were masked in both files and cannot be compared.");
        }
        return new Part<>(true, null, evidence, rows, unchanged, notes);
    }

    // ---------------------------------------------------------------- clients

    private static Part<ClientChange> consumers(SnapshotFile before, SnapshotFile after)
    {
        return clients("consumer listing", before.consumers(), after.consumers(),
                (was, is) -> FieldChange.of("deliveringCount", "messages",
                        was == null ? Value.missing("not there", "") : was.deliveringCount(),
                        is == null ? Value.missing("not there", "") : is.deliveringCount()),
                row -> "on " + row.queue());
    }

    private static Part<ClientChange> connections(SnapshotFile before, SnapshotFile after)
    {
        return clients("connection listing", before.connections(), after.connections(),
                (was, is) -> FieldChange.of("sessionCount", "sessions",
                        was == null ? Value.missing("not there", "") : was.sessionCount(),
                        is == null ? Value.missing("not there", "") : is.sessionCount()),
                row -> "from " + row.clientAddress());
    }

    private static <T> Part<ClientChange> clients(String section, SnapshotFile.Listing<T> before,
            SnapshotFile.Listing<T> after, BiFunction<T, T, FieldChange> level,
            java.util.function.Function<T, String> describe)
    {
        String evidence = evidence(section, before.rows().collectedAt(), after.rows().collectedAt());
        String why = notRead(before.rows(), after.rows());
        if (why != null)
        {
            return Part.notCompared(why, evidence);
        }
        Map<String, T> b = before.rows().data();
        Map<String, T> a = after.rows().data();
        List<ClientChange> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int unchanged = 0;
        for (String id : union(b.keySet(), a.keySet()))
        {
            T was = b.get(id);
            T is = a.get(id);
            FieldChange change = level.apply(was, is);
            if (was == null)
            {
                rows.add(before.truncated()
                        ? new ClientChange(ClientPresence.NOT_IN_KEPT_ROWS, id, describe.apply(is), change,
                                "Not among the earlier snapshot's kept rows; it may have been there already.", evidence)
                        : new ClientChange(ClientPresence.APPEARED, id, describe.apply(is), change, "", evidence));
            }
            else if (is == null)
            {
                rows.add(after.truncated()
                        ? new ClientChange(ClientPresence.NOT_IN_KEPT_ROWS, id, describe.apply(was), change,
                                "Not among the later snapshot's kept rows; it may still be there.", evidence)
                        : new ClientChange(ClientPresence.GONE, id, describe.apply(was), change, "", evidence));
            }
            else if (change.changed())
            {
                rows.add(new ClientChange(ClientPresence.CHANGED, id, describe.apply(is), change, "", evidence));
            }
            else
            {
                unchanged++;
            }
        }
        rows.sort(Comparator.comparing(ClientChange::presence).thenComparing(ClientChange::id));
        if (before.truncated() || after.truncated())
        {
            notes.add("A listing was cut short (" + before.rows().data().size() + " of " + before.total()
                    + " rows kept earlier, " + after.rows().data().size() + " of " + after.total()
                    + " later), so a row missing from it is not known to have come or gone.");
        }
        return new Part<>(true, null, evidence, rows, unchanged, notes);
    }

    // ---------------------------------------------------------------- findings

    private static Part<FindingChange> findings(SnapshotFile before, SnapshotFile after)
    {
        var b = before.diagnosis();
        var a = after.diagnosis();
        String evidence = evidence("diagnose findings", b.collectedAt(), a.collectedAt());
        String why = notRead(b, a);
        if (why != null)
        {
            return Part.notCompared(why, evidence);
        }
        Map<String, SnapshotFile.FindingRow> was = byIdentity(b.data().findings());
        Map<String, SnapshotFile.FindingRow> is = byIdentity(a.data().findings());
        List<FindingChange> rows = new ArrayList<>();
        int unchanged = 0;
        for (String key : union(was.keySet(), is.keySet()))
        {
            SnapshotFile.FindingRow then = was.get(key);
            SnapshotFile.FindingRow now = is.get(key);
            if (then == null)
            {
                rows.add(new FindingChange(FindingPresence.NEW, null, now, evidence));
            }
            else if (now == null)
            {
                rows.add(new FindingChange(FindingPresence.RESOLVED, then, null, evidence));
            }
            else if (!same(then.severity(), now.severity()) || !same(then.title(), now.title()))
            {
                rows.add(new FindingChange(FindingPresence.CHANGED, then, now, evidence));
            }
            else
            {
                unchanged++;
            }
        }
        rows.sort(Comparator.comparing(FindingChange::presence));
        List<String> notes = new ArrayList<>();
        if (!a.data().couldNotCheck().isEmpty())
        {
            notes.add("The later diagnose could not check " + a.data().couldNotCheck().size()
                    + " thing(s), so a finding it did not repeat may not have been looked for rather than resolved: "
                    + String.join("; ", a.data().couldNotCheck()));
        }
        if (!b.data().couldNotCheck().isEmpty())
        {
            notes.add("The earlier diagnose could not check " + b.data().couldNotCheck().size()
                    + " thing(s), so a new finding may have been there already: "
                    + String.join("; ", b.data().couldNotCheck()));
        }
        return new Part<>(true, null, evidence, rows, unchanged, notes);
    }

    private static Map<String, SnapshotFile.FindingRow> byIdentity(List<SnapshotFile.FindingRow> findings)
    {
        Map<String, SnapshotFile.FindingRow> map = new LinkedHashMap<>();
        findings.forEach(f -> map.putIfAbsent(f.identity(), f));
        return map;
    }

    // ---------------------------------------------------------------- helpers

    /** Why a section cannot be compared — which side, and the snapshot's own reason — or null when both were read. */
    private static String notRead(SnapshotFile.Section<?> before, SnapshotFile.Section<?> after)
    {
        List<String> parts = new ArrayList<>();
        if (!before.available())
        {
            parts.add("the earlier snapshot could not read it (" + before.why()
                    + (before.detail().isBlank() ? "" : " — " + before.detail()) + ")");
        }
        if (!after.available())
        {
            parts.add("the later snapshot could not read it (" + after.why()
                    + (after.detail().isBlank() ? "" : " — " + after.detail()) + ")");
        }
        if (parts.isEmpty())
        {
            return null;
        }
        return "Not compared: " + String.join(", and ", parts)
                + ". Nothing in it is shown as added or removed, because one side is not known.";
    }

    private static List<FieldChange> fields(Map<String, Value> before, Map<String, Value> after,
            Map<String, String> units)
    {
        List<FieldChange> rows = new ArrayList<>();
        for (String name : union(before.keySet(), after.keySet()))
        {
            rows.add(FieldChange.of(name, units.getOrDefault(name, ""), get(before, name), get(after, name)));
        }
        return rows;
    }

    /** Settings that differ, or that one side read and the other could not. */
    private static List<SettingChange> settingChanges(Map<String, Value> before, Map<String, Value> after)
    {
        List<SettingChange> changes = new ArrayList<>();
        for (String key : union(before.keySet(), after.keySet()))
        {
            Value was = get(before, key);
            Value is = get(after, key);
            if (was.differsFrom(is))
            {
                changes.add(new SettingChange(key, was, is, true));
            }
            else if (was.known() != is.known())
            {
                changes.add(new SettingChange(key, was, is, false));
            }
        }
        return changes;
    }

    private static Value get(Map<String, Value> values, String name)
    {
        return values.getOrDefault(name, Value.missing(Value.NOT_IN_FILE, ""));
    }

    private static List<String> union(Set<String> first, Set<String> second)
    {
        Set<String> all = new LinkedHashSet<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static boolean same(String a, String b)
    {
        return a == null ? b == null : a.equals(b);
    }

    static String evidence(String section, Instant before, Instant after)
    {
        return section + ", read " + at(before, after) + " → " + at(after, before) + " UTC";
    }

    private static String window(SnapshotFile file)
    {
        return DAY_TIME.format(file.startedAt()) + "–" + TIME.format(file.finishedAt());
    }

    /** A time of day, with the date when the other side's is on a different day. */
    private static String at(Instant time, Instant other)
    {
        if (time == null)
        {
            return "(time not recorded)";
        }
        boolean sameDay = other == null
                || time.atZone(ZoneOffset.UTC).toLocalDate().equals(other.atZone(ZoneOffset.UTC).toLocalDate());
        return (sameDay ? TIME : DAY_TIME).format(time);
    }
}
