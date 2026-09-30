package com.culberth.tools.artemisbrowser.broker;

import com.culberth.tools.artemisbrowser.broker.DeadLetterTriage.GroupBy;
import com.culberth.tools.artemisbrowser.broker.DeadLetterTriage.Origin;
import com.culberth.tools.artemisbrowser.broker.DeadLetterTriage.PropertySeen;
import com.culberth.tools.artemisbrowser.broker.DeadLetterTriage.ValueGroup;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Groups a bounded sample of one queue by where its messages came from.
 *
 * <p>
 * The sample is read with management {@code browse}, page by page from the head of the queue, so the broker does the
 * paging and nothing is consumed; it stops at the sample size, which is capped by {@code artemis.triage.max-sample}.
 * Grouping happens here, over at most that many rows. Then two things are looked up per origin, both bounded: whether
 * the origin queue is still on the broker (from the one listing already read), and the origin address's current
 * settings, for at most {@code artemis.triage.max-settings-reads} addresses, the largest groups first — which is what
 * lets a group say "its address dead-letters here" as an observation about settings now, not about the past.
 */
@Service
public class DeadLetterTriageService
{

    /** A property value is grouped and shown up to this many characters. */
    static final int MAX_VALUE_CHARS = 200;
    /** Rows per browse call, or the sample size when that is smaller. */
    static final int PAGE_SIZE = 200;
    static final int EXAMPLES = 3;
    static final int MAX_PROPERTIES = 60;

    /*
     * Where each piece of origin metadata is, in a browse row. A core message carries the broker's own property names;
     * an AMQP message shows the same values under "extraProperties." and as "x-opt-" message annotations — verified on
     * 2.55.0 and 2.57.0 with an AMQP sender dead-lettered by a core consumer.
     */
    static final List<String> ORIGIN_ADDRESS = List.of("_AMQ_ORIG_ADDRESS", "extraProperties._AMQ_ORIG_ADDRESS",
            "messageAnnotations.x-opt-ORIG-ADDRESS");
    static final List<String> ORIGIN_QUEUE = List.of("_AMQ_ORIG_QUEUE", "extraProperties._AMQ_ORIG_QUEUE",
            "messageAnnotations.x-opt-ORIG-QUEUE");
    static final List<String> ORIGIN_ROUTING = List.of("_AMQ_ORIG_ROUTING_TYPE",
            "extraProperties._AMQ_ORIG_ROUTING_TYPE", "messageAnnotations.x-opt-ORIG-ROUTING-TYPE");
    static final List<String> ACTUAL_EXPIRY = List.of("_AMQ_ACTUAL_EXPIRY", "extraProperties._AMQ_ACTUAL_EXPIRY",
            "messageAnnotations.x-opt-ACTUAL-EXPIRY");

    private final BrokerSession brokerSession;
    private final QueueDirectory queueDirectory;
    private final QueueBrowseService browseService;
    private final AddressDirectory addressDirectory;
    private final int defaultSample;
    private final int maxSample;
    private final int maxGroups;
    private final int maxSettingsReads;

    public DeadLetterTriageService(BrokerSession brokerSession, QueueDirectory queueDirectory,
            QueueBrowseService browseService, AddressDirectory addressDirectory,
            @Value("${artemis.triage.default-sample:500}") int defaultSample,
            @Value("${artemis.triage.max-sample:2000}") int maxSample,
            @Value("${artemis.triage.max-groups:50}") int maxGroups,
            @Value("${artemis.triage.max-settings-reads:20}") int maxSettingsReads)
    {
        this.brokerSession = brokerSession;
        this.queueDirectory = queueDirectory;
        this.browseService = browseService;
        this.addressDirectory = addressDirectory;
        this.maxSample = Math.max(1, maxSample);
        this.defaultSample = Math.clamp(defaultSample, 1, this.maxSample);
        this.maxGroups = Math.max(1, maxGroups);
        this.maxSettingsReads = Math.max(0, maxSettingsReads);
    }

    public int defaultSample()
    {
        return defaultSample;
    }

    public int maxSample()
    {
        return maxSample;
    }

    /**
     * A triage of one queue, or null when the broker does not list a queue by that name.
     *
     * @param sample  how many messages to read; out of range becomes the default or the limit
     * @param groupBy a property name to group by as well, or blank for none
     */
    public DeadLetterTriage triage(String queueName, String filter, int sample, String groupBy)
    {
        long callsBefore = brokerSession.managementCalls();
        Instant started = Instant.now();
        List<QueueOverview> queues = queueDirectory.overview();
        QueueOverview queue = queues.stream().filter(q -> q.name().equals(queueName)).findFirst().orElse(null);
        if (queue == null)
        {
            return null;
        }
        int requested = sample <= 0 ? defaultSample : Math.min(sample, maxSample);
        String effectiveFilter = filter == null ? "" : filter.trim();
        // One size for every page, since a page is an offset of it; never more than the sample, so a short page
        // means the queue ran out rather than that more was asked for than kept.
        int pageSize = Math.min(PAGE_SIZE, requested);

        List<MessageSummary> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        boolean reachedEnd = false;
        for (int page = 1; rows.size() < requested; page++)
        {
            List<MessageSummary> got = browseService.rows(queueName, effectiveFilter, page, pageSize);
            for (MessageSummary message : got)
            {
                String key = message.coreId() != null ? message.coreId() : message.messageId();
                if (key != null && !seen.add(key))
                {
                    // The queue moved between two pages and this row was read twice.
                    duplicates++;
                }
                else if (rows.size() < requested)
                {
                    rows.add(message);
                }
            }
            if (got.size() < pageSize)
            {
                reachedEnd = true;
                break;
            }
        }

        Long after;
        try
        {
            QueueStats stats = queueDirectory.stats(queueName);
            after = stats == null ? null : stats.messageCount();
        }
        catch (BrokerException e)
        {
            after = null;
        }

        Grouping grouping = group(rows, groupBy, maxGroups);
        Set<String> names = new HashSet<>();
        queues.forEach(q -> names.add(q.name()));

        Map<String, Reading<AddressSettings>> settings = new HashMap<>();
        int settingsNotRead = 0;
        List<Origin> origins = new ArrayList<>();
        for (Origin origin : grouping.origins())
        {
            Reading<AddressSettings> read = null;
            if (origin.address() != null)
            {
                read = settings.get(origin.address());
                if (read == null)
                {
                    if (settings.size() < maxSettingsReads)
                    {
                        read = Reading.attempt(() -> addressDirectory.settings(origin.address()));
                    }
                    else
                    {
                        read = Reading.notCollected(
                                "past the limit of " + maxSettingsReads + " origin addresses whose settings are read");
                        settingsNotRead++;
                    }
                    settings.put(origin.address(), read);
                }
            }
            Boolean exists = origin.queue() == null ? null : names.contains(origin.queue());
            origins.add(new Origin(origin.address(), origin.queue(), origin.routingTypes(), origin.count(),
                    origin.expired(), origin.firstSent(), origin.lastSent(), origin.firstExpired(),
                    origin.lastExpired(), origin.protocols(), origin.examples(), exists, read, queue.address()));
        }

        return new DeadLetterTriage(queueName, queue.address(), effectiveFilter, queue.messageCount(), after,
                queue.deliveringCount(), queue.scheduledCount(), requested, rows.size(), reachedEnd, duplicates,
                pageSize, maxGroups, maxSettingsReads, started, Instant.now(),
                brokerSession.managementCalls() - callsBefore, origins, grouping.originsNotListed(),
                grouping.messagesNotListed(), grouping.noOrigin(), grouping.partialOrigin(), grouping.expired(),
                settingsNotRead, grouping.properties(), grouping.propertiesNotListed(), grouping.groupBy());
    }

    /** The sample grouped, before anything else is looked up. */
    record Grouping(List<Origin> origins, int originsNotListed, int messagesNotListed, int noOrigin, int partialOrigin,
            int expired, List<PropertySeen> properties, int propertiesNotListed, GroupBy groupBy)
    {
    }

    /**
     * Groups rows by origin, counts property names, and — when asked — groups by one property's value. Pure: no broker
     * call, so the grouping rules are tested on rows alone. Origins are listed largest first, at most {@code maxGroups}
     * of them, and the messages without an origin are always listed.
     */
    static Grouping group(List<MessageSummary> rows, String groupBy, int maxGroups)
    {
        Map<List<String>, OriginTally> origins = new LinkedHashMap<>();
        Map<String, Integer> propertyCounts = new HashMap<>();
        Map<String, ValueTally> values = new LinkedHashMap<>();
        String property = groupBy == null || groupBy.isBlank() ? null : groupBy.trim();
        int noOrigin = 0;
        int partialOrigin = 0;
        int expired = 0;
        int notSet = 0;

        for (MessageSummary row : rows)
        {
            Map<String, String> properties = row.properties() == null ? Map.of() : row.properties();
            properties.keySet().forEach(name -> propertyCounts.merge(name, 1, Integer::sum));

            String address = first(properties, ORIGIN_ADDRESS);
            String queue = first(properties, ORIGIN_QUEUE);
            if (address == null && queue == null)
            {
                noOrigin++;
            }
            else if (queue == null)
            {
                partialOrigin++;
            }
            Long expiredAt = asMillis(first(properties, ACTUAL_EXPIRY));
            if (expiredAt != null)
            {
                expired++;
            }
            OriginTally tally = origins.computeIfAbsent(java.util.Arrays.asList(address, queue),
                    key -> new OriginTally(address, queue));
            tally.add(row, routing(first(properties, ORIGIN_ROUTING)), expiredAt);

            if (property != null)
            {
                String value = properties.get(property);
                if (value == null)
                {
                    notSet++;
                }
                else
                {
                    boolean cut = value.length() > MAX_VALUE_CHARS;
                    String key = cut ? value.substring(0, MAX_VALUE_CHARS) : value;
                    values.computeIfAbsent(key, k -> new ValueTally(k)).add(row, tally.label(), cut);
                }
            }
        }

        List<OriginTally> sorted = new ArrayList<>(origins.values());
        sorted.sort(Comparator.comparingInt(OriginTally::count).reversed().thenComparing(OriginTally::label));
        List<Origin> listed = new ArrayList<>();
        int notListed = 0;
        int messagesNotListed = 0;
        for (OriginTally tally : sorted)
        {
            if (listed.size() < maxGroups || !tally.known())
            {
                listed.add(tally.toOrigin());
            }
            else
            {
                notListed++;
                messagesNotListed += tally.count;
            }
        }

        List<PropertySeen> seen = propertyCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_PROPERTIES).map(e -> new PropertySeen(e.getKey(), e.getValue(), bookkeeping(e.getKey())))
                .toList();

        GroupBy grouped = null;
        if (property != null)
        {
            List<ValueTally> byCount = new ArrayList<>(values.values());
            byCount.sort(Comparator.comparingInt(ValueTally::count).reversed().thenComparing(v -> v.value));
            List<ValueGroup> valueGroups = byCount.stream().limit(maxGroups).map(ValueTally::toGroup).toList();
            int valuesNotListed = Math.max(0, byCount.size() - maxGroups);
            int valueMessagesNotListed = byCount.stream().skip(maxGroups).mapToInt(ValueTally::count).sum();
            grouped = new GroupBy(property, valueGroups, notSet, valuesNotListed, valueMessagesNotListed);
        }

        return new Grouping(listed, notListed, messagesNotListed, noOrigin, partialOrigin, expired, seen,
                Math.max(0, propertyCounts.size() - MAX_PROPERTIES), grouped);
    }

    /** Set by the broker or the protocol rather than by whoever sent the message. */
    static boolean bookkeeping(String name)
    {
        return name.startsWith("_AMQ") || name.startsWith("__AMQ") || name.startsWith("extraProperties.")
                || name.startsWith("messageAnnotations.") || name.startsWith("properties.")
                || name.startsWith("__HDR_");
    }

    private static String first(Map<String, String> properties, List<String> names)
    {
        for (String name : names)
        {
            String value = properties.get(name);
            if (value != null && !value.isBlank())
            {
                return value;
            }
        }
        return null;
    }

    /** {@code _AMQ_ORIG_ROUTING_TYPE} is a byte: 0 multicast, 1 anycast, as the broker's own RoutingType. */
    private static String routing(String value)
    {
        if (value == null)
        {
            return null;
        }
        return switch (value.trim())
        {
            case "0" -> "multicast";
            case "1" -> "anycast";
            default -> "routing type " + value.trim();
        };
    }

    private static Long asMillis(String value)
    {
        if (value == null)
        {
            return null;
        }
        try
        {
            long millis = Long.parseLong(value.trim());
            return millis > 0 ? millis : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static final class OriginTally
    {

        private final String address;
        private final String queue;
        private final Set<String> routingTypes = new LinkedHashSet<>();
        private final Set<String> protocols = new LinkedHashSet<>();
        private final List<String> examples = new ArrayList<>();
        private int count;
        private int expired;
        private Long firstSent;
        private Long lastSent;
        private Long firstExpired;
        private Long lastExpired;

        OriginTally(String address, String queue)
        {
            this.address = address;
            this.queue = queue;
        }

        void add(MessageSummary row, String routing, Long expiredAt)
        {
            count++;
            if (routing != null)
            {
                routingTypes.add(routing);
            }
            if (row.protocol() != null)
            {
                protocols.add(row.protocol());
            }
            if (row.messageId() != null && examples.size() < EXAMPLES)
            {
                examples.add(row.messageId());
            }
            if (row.timestamp() != null)
            {
                firstSent = firstSent == null ? row.timestamp() : Math.min(firstSent, row.timestamp());
                lastSent = lastSent == null ? row.timestamp() : Math.max(lastSent, row.timestamp());
            }
            if (expiredAt != null)
            {
                expired++;
                firstExpired = firstExpired == null ? expiredAt : Math.min(firstExpired, expiredAt);
                lastExpired = lastExpired == null ? expiredAt : Math.max(lastExpired, expiredAt);
            }
        }

        int count()
        {
            return count;
        }

        boolean known()
        {
            return address != null || queue != null;
        }

        String label()
        {
            return known() ? (address == null ? "?" : address) + " / " + (queue == null ? "queue not recorded" : queue)
                    : "no origin recorded";
        }

        Origin toOrigin()
        {
            return new Origin(address, queue, List.copyOf(routingTypes), count, expired, firstSent, lastSent,
                    firstExpired, lastExpired, List.copyOf(protocols), List.copyOf(examples), null, null, null);
        }
    }

    private static final class ValueTally
    {

        private final String value;
        private final Set<String> origins = new LinkedHashSet<>();
        private final List<String> examples = new ArrayList<>();
        private boolean truncated;
        private int count;

        ValueTally(String value)
        {
            this.value = value;
        }

        void add(MessageSummary row, String origin, boolean cut)
        {
            count++;
            truncated |= cut;
            origins.add(origin);
            if (row.messageId() != null && examples.size() < EXAMPLES)
            {
                examples.add(row.messageId());
            }
        }

        int count()
        {
            return count;
        }

        ValueGroup toGroup()
        {
            return new ValueGroup(value, truncated, count, origins.stream().limit(EXAMPLES).toList(), origins.size(),
                    List.copyOf(examples));
        }
    }
}
