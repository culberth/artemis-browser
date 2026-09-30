package com.culberth.tools.artemisbrowser.compare;

import java.time.Duration;
import java.util.List;

/**
 * What observably changed between two saved snapshots of one broker, and how far each difference can be trusted.
 *
 * <p>
 * Every difference carries the evidence it came from — which section of the files, read when in each — because the two
 * snapshots are sequences of reads, not instants. A value either side could not read is shown as missing, never as a
 * change; a queue absent from a section that was not read is not "removed"; and a counter across a restart, a recreated
 * queue or a reset is shown as two readings, not as traffic.
 *
 * @param reordered     true when the files were given latest first and were put in time order
 * @param elapsedMillis from the earlier snapshot's start to the later one's
 * @param health        every broker attribute either side recorded, changed or not
 */
public record SnapshotComparison(SnapshotFile before, SnapshotFile after, boolean reordered, long elapsedMillis,
        Identity identity, Continuity continuity, List<FieldChange> health, Part<QueueChange> queues,
        Part<AddressChange> addresses, Part<SettingsChange> settings, Part<ClientChange> consumers,
        Part<ClientChange> connections, Part<FindingChange> findings)
{

    public String elapsedText()
    {
        return durationText(elapsedMillis);
    }

    static String durationText(long millis)
    {
        Duration d = Duration.ofMillis(Math.abs(millis));
        String sign = millis < 0 ? "-" : "";
        if (d.toHours() > 0)
        {
            return sign + d.toHours() + "h " + d.toMinutesPart() + "m";
        }
        if (d.toMinutes() > 0)
        {
            return sign + d.toMinutes() + "m " + d.toSecondsPart() + "s";
        }
        return sign + d.toSeconds() + "." + (d.toMillisPart() / 100) + "s";
    }

    /**
     * Why the two files are taken to describe one broker.
     *
     * @param confirmed false when only the address matched, because a node id was missing
     */
    public record Identity(String basis, boolean confirmed, List<String> notes)
    {
    }

    /** Whether the broker ran without a restart between the two snapshots, which decides whether counters subtract. */
    public record Continuity(Kind kind, String explanation)
    {

        public enum Kind
        {
            CONTINUOUS, RESTARTED, UNKNOWN
        }

        public boolean continuous()
        {
            return kind == Kind.CONTINUOUS;
        }

        public boolean restarted()
        {
            return kind == Kind.RESTARTED;
        }
    }

    /**
     * One part of the comparison: its rows, or why it was not compared.
     *
     * @param evidence  which section of the files, read when on each side
     * @param unchanged how many things were in both and did not change
     * @param notes     what limits the rows — truncation, omissions, what could not be checked
     */
    public record Part<T>(boolean compared, String why, String evidence, List<T> rows, int unchanged,
            List<String> notes)
    {

        static <T> Part<T> notCompared(String why, String evidence)
        {
            return new Part<>(false, why, evidence, List.of(), 0, List.of());
        }
    }

    /**
     * One value on both sides.
     *
     * @param unit  what the number counts — "messages", "bytes", "%" — or empty for text
     * @param delta the signed difference when both sides are whole numbers, else null
     */
    public record FieldChange(String name, String unit, Value before, Value after, boolean changed, String delta)
    {

        static FieldChange of(String name, String unit, Value before, Value after)
        {
            String delta = before.isNumber() && after.isNumber() ? signed(after.number() - before.number()) : null;
            return new FieldChange(name, unit, before, after, before.differsFrom(after), delta);
        }

        /** "7 → 12 messages (+5)"; a missing side keeps its reason, and there is no difference beside it. */
        public String text()
        {
            return pair() + (changed && delta != null ? " (" + delta + ")" : "");
        }

        /**
         * A running total, with its difference only when the status allows one: in full when counted, as "if unbroken"
         * when uncertain, and not at all across a restart, recreation or reset.
         */
        public String counterText(CounterStatus status)
        {
            if (!changed || delta == null || status == null)
            {
                return pair();
            }
            return switch (status)
            {
                case COUNTED -> pair() + " (" + delta + ")";
                case UNCERTAIN -> pair() + " (" + delta + " if unbroken)";
                default -> pair() + " (not traffic)";
            };
        }

        /** Both values, the unit once at the end when both have one, else beside the side that does. */
        private String pair()
        {
            if (unit.isEmpty())
            {
                return before.display() + " → " + after.display();
            }
            if (before.known() && after.known())
            {
                return before.display() + " → " + after.display() + " " + unit;
            }
            return withUnit(before) + " → " + withUnit(after);
        }

        private String withUnit(Value value)
        {
            return value.known() ? value.display() + " " + unit : value.display();
        }

        /** Whether one side has a value and the other could not give one — shown, but not as a change. */
        public boolean oneSided()
        {
            return before.known() != after.known();
        }
    }

    static String signed(long value)
    {
        return value > 0 ? "+" + value : Long.toString(value);
    }

    /**
     * One setting on both sides.
     *
     * @param comparable false when a side could not read it: shown, but not as a change
     */
    public record SettingChange(String key, Value before, Value after, boolean comparable)
    {
    }

    /**
     * How far a queue's running totals may be subtracted.
     */
    public enum CounterStatus
    {
        /** Same queue, same broker run, nothing went back: the difference is what happened in between. */
        COUNTED("Counted between the snapshots."),
        /** The broker restarted: counters start again from what the journal reloads. */
        RESTART("The broker restarted in between; its counters started again, so the difference is not traffic."),
        /** The queue was deleted and made again: a new id, new counters. */
        RECREATED("The queue was recreated in between (its id changed); its counters are a new queue's."),
        /** A counter went down without a restart that could be seen: reset, or a restart not recorded. */
        RESET("A counter went down: reset, or a restart the snapshots could not show. The difference is not traffic."),
        /** No way to rule out a restart or recreation. */
        UNCERTAIN("A restart or recreation cannot be ruled out, so the difference may not be traffic.");

        private final String meaning;

        CounterStatus(String meaning)
        {
            this.meaning = meaning;
        }

        public String meaning()
        {
            return meaning;
        }

        public boolean traffic()
        {
            return this == COUNTED;
        }
    }

    public enum Presence
    {
        ADDED, REMOVED, RECREATED, CHANGED
    }

    /**
     * One queue that differs.
     *
     * @param levels        messages, in flight, scheduled, consumers, paused — comparable across any gap
     * @param counters      added, acknowledged, expired, killed — see {@code counterStatus}
     * @param configuration settings that differ or that one side could not read
     */
    public record QueueChange(String name, String address, boolean internal, Presence presence,
            List<FieldChange> levels, CounterStatus counterStatus, String counterNote, List<FieldChange> counters,
            List<SettingChange> configuration, String evidence)
    {

        public long depthChange()
        {
            return levels.stream().filter(l -> l.name().equals("messageCount") && l.delta() != null).findFirst()
                    .map(l -> Math.abs(Long.parseLong(l.delta()))).orElse(0L);
        }
    }

    /** One address that appeared, went, or holds a different amount. */
    public record AddressChange(String name, boolean internal, Presence presence, List<FieldChange> levels,
            String evidence)
    {
    }

    /** One address whose settings differ, or could be read on one side only. */
    public record SettingsChange(String address, List<SettingChange> changes, String note, String evidence)
    {
    }

    public enum ClientPresence
    {
        APPEARED, GONE, CHANGED,
        /** Missing from a listing that was cut short: it may be there, past the rows kept. */
        NOT_IN_KEPT_ROWS
    }

    /** One consumer or connection that came, went or changed. */
    public record ClientChange(ClientPresence presence, String id, String description, FieldChange level, String note,
            String evidence)
    {
    }

    public enum FindingPresence
    {
        NEW, RESOLVED, CHANGED
    }

    /** One diagnose finding that is new, gone, or changed in severity or in its counts. */
    public record FindingChange(FindingPresence presence, SnapshotFile.FindingRow before, SnapshotFile.FindingRow after,
            String evidence)
    {

        /** The side that exists — the later one when both do. */
        public SnapshotFile.FindingRow current()
        {
            return after != null ? after : before;
        }
    }
}
