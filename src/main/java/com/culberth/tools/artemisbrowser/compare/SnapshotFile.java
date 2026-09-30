package com.culberth.tools.artemisbrowser.compare;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One saved incident snapshot, read back from its JSON into only what a comparison needs.
 *
 * <p>
 * Built by {@link SnapshotReader}, which has already checked its kind, schema version, collection time and size. What
 * the broker would not give when the snapshot was taken stays missing here — a {@link Section} that is not available,
 * or a {@link Value} with its reason — so nothing downstream can mistake it for zero or for "gone".
 *
 * @param label           what the person called it: the uploaded file's name
 * @param healthCollected when the health attributes were read, or null when the file does not say
 * @param health          the broker attributes by the snapshot's own field names, in the snapshot's order
 * @param addressSettings settings by address, for the addresses the snapshot chose to read; a missing entry holds the
 *                        reason in {@link Section#why()}
 * @param unavailable     the snapshot's own list of what it could not collect, one line each
 * @param omitted         the snapshot's own list of what it left out on purpose, one line each
 * @param limits          the bounds it was collected under, by the snapshot's own names
 */
public record SnapshotFile(String label, int schemaVersion, Instant startedAt, Instant finishedAt, String host,
        String port, Instant healthCollected, Map<String, Value> health, Section<Map<String, QueueRow>> queues,
        Section<Map<String, AddressRow>> addresses, Map<String, Section<Map<String, Value>>> addressSettings,
        Listing<ConsumerRow> consumers, Listing<ConnectionRow> connections, Section<Diagnosis> diagnosis,
        List<String> unavailable, List<String> omitted, Map<String, Value> limits)
{

    public Value nodeId()
    {
        return health.getOrDefault("nodeId", Value.missing(Value.NOT_IN_FILE, ""));
    }

    public Value uptimeMillis()
    {
        return health.getOrDefault("uptimeMillis", Value.missing(Value.NOT_IN_FILE, ""));
    }

    public String address()
    {
        return host + ":" + port;
    }

    /** The health section's own time where recorded, else when collection started. */
    public Instant healthTime()
    {
        return healthCollected != null ? healthCollected : startedAt;
    }

    /**
     * A section as the snapshot recorded it: its data, or why there is none.
     *
     * @param collectedAt when it was read, or null when the file does not say
     */
    public record Section<T>(T data, String why, String detail, Instant collectedAt)
    {

        public static <T> Section<T> of(T data, Instant collectedAt)
        {
            return new Section<>(data, null, "", collectedAt);
        }

        public static <T> Section<T> missing(String why, String detail, Instant collectedAt)
        {
            return new Section<>(null, why, detail == null ? "" : detail, collectedAt);
        }

        public boolean available()
        {
            return why == null;
        }
    }

    /**
     * A client listing, which the snapshot may have cut short.
     *
     * @param total how many rows the broker had; more than {@code rows} holds when the snapshot kept only the first
     */
    public record Listing<T>(Section<Map<String, T>> rows, long total, boolean truncated)
    {
    }

    /**
     * One queue.
     *
     * @param levels        how much it held at that moment — messages, in flight, scheduled, consumers — which can be
     *                      compared across any gap
     * @param counters      running totals since the broker started or the queue was created, which can only be
     *                      subtracted across an unbroken stretch
     * @param configuration its effective settings as listed
     */
    public record QueueRow(String name, Value id, String address, boolean internal, Map<String, Value> levels,
            Map<String, Value> counters, Map<String, Value> configuration)
    {
    }

    /** One address, with how much it held. */
    public record AddressRow(String name, String routingTypes, boolean internal, Map<String, Value> levels)
    {
    }

    /** One consumer, by {@code connection:session:consumer}. */
    public record ConsumerRow(String key, String queue, Value deliveringCount)
    {
    }

    /** One client connection, by connection id. */
    public record ConnectionRow(String connectionId, String clientAddress, Value sessionCount)
    {
    }

    /** Diagnose's findings, and what it said it could not check. */
    public record Diagnosis(List<FindingRow> findings, List<String> couldNotCheck)
    {
    }

    /** One finding as written. */
    public record FindingRow(String severity, String basis, String title, String detail, String queue, String address,
            String clientId, String connectionId)
    {

        /**
         * What makes two findings the same one across snapshots: where it points and its title with any counts outside
         * quoted names blanked — "'orders' is holding 7 message(s)" and "... 9 message(s)" are one finding that
         * changed, while "'orders-1'" and "'orders-2'" stay two. Severity is left out so an escalation shows as a
         * change, not as one finding resolved and another new.
         */
        public String identity()
        {
            return String.join("\u0000", withoutCounts(title), nz(queue), nz(address), nz(clientId), nz(connectionId));
        }

        static String withoutCounts(String title)
        {
            if (title == null)
            {
                return "";
            }
            String[] parts = title.split("'", -1);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < parts.length; i++)
            {
                if (i > 0)
                {
                    out.append('\'');
                }
                out.append(i % 2 == 0 ? parts[i].replaceAll("\\d+", "#") : parts[i]);
            }
            return out.toString();
        }

        private static String nz(String value)
        {
            return value == null ? "" : value;
        }
    }
}
