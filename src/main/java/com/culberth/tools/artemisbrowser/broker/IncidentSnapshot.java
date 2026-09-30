package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Operational evidence about one broker, collected together for an incident: who it is, how it is doing, what its
 * queues and addresses hold, who is attached, how things changed this session, and what diagnose made of it.
 *
 * <p>
 * A sequence of observations, not an atomic picture: each section was read after the one before, and carries its own
 * time. Everything the broker would not give is a {@link Reading} with its reason, so an offline reader can tell "zero"
 * from "not permitted" from "not asked". Message bodies are never included; credentials are never held, and
 * secret-looking settings are masked ({@link Redaction}).
 *
 * @param addressSettings        settings for the addresses chosen to include, by address; see
 *                               {@code addressSettingsOmitted}
 * @param addressSettingsOmitted addresses whose settings were not read because of the limit
 * @param connectivity           HA state, topology, cluster connections, bridges and broker connections, as this broker
 *                               reports them
 * @param transactions           prepared XA branches and those resolved by hand; message headers only, no properties
 * @param permissions            the roles reported for a bounded set of addresses, and whether security is enforced
 * @param trends                 this session's trend history as of the snapshot — observations it made, never older
 * @param diagnosis              diagnose's findings, or why they could not be made
 */
public record IncidentSnapshot(Instant startedAt, Instant finishedAt, ConnectionInfo connection, BrokerHealth health,
        Reading<List<QueueOverview>> queues, Reading<List<AddressOverview>> addresses,
        Map<String, Reading<AddressSettings>> addressSettings, int addressSettingsOmitted,
        Listing<AcceptorInfo> acceptors, Listing<BrokerConnection> connections, Listing<BrokerConsumer> consumers,
        Listing<BrokerProducer> producers, Reading<Connectivity> connectivity, Reading<Transactions> transactions,
        Reading<Permissions> permissions, Trends trends, Reading<Diagnosis> diagnosis, Limits limits)
{

    /** Bumped whenever a field changes meaning or is removed, so two saved snapshots can be compared knowingly. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * A listing, perhaps cut short.
     *
     * @param total how many rows the broker had; more than {@code rows} holds when the snapshot kept only the first
     */
    public record Listing<T>(Reading<List<T>> rows, int total)
    {

        public static <T> Listing<T> of(Reading<List<T>> read, int max)
        {
            if (!read.available())
            {
                return new Listing<>(read, 0);
            }
            List<T> all = read.value();
            return new Listing<>(all.size() > max ? read.map(list -> List.copyOf(list.subList(0, max))) : read,
                    all.size());
        }

        public boolean truncated()
        {
            return rows.available() && total > rows.value().size();
        }
    }

    /** The bounds the snapshot was collected under, recorded so a reader knows what "all" meant. */
    public record Limits(int maxRowsPerListing, int maxAddressSettings, long trendSpacingMillis, int trendMaxReadings,
            int trendMaxQueues, int maxAddressPermissions, int transactionDetailLimit)
    {
    }

    public long durationMillis()
    {
        return finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }
}
