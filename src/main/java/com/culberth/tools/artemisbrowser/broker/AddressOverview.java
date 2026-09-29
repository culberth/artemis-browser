package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * One address and the queues bound to it.
 *
 * <p>
 * An address is the thing producers send to; queues are what consumers read from. For point-to-point traffic the two
 * line up one-to-one and the distinction is invisible, which is why a queue-only view gets you a long way. It stops
 * being invisible with multicast: one address fans out to a queue per subscriber, each holding its own copy, and none
 * of them named after the address.
 *
 * <p>
 * The storage figures come from the same {@code listAddresses} call, and are read as {@link Reading}s because their
 * meaning is narrower than their names. Verified on 2.55.0 and 2.57.0:
 * <ul>
 * <li>{@code addressSize} is the broker's in-memory estimate, in bytes — not the journal and not the disk.</li>
 * <li>{@code addressLimitPercent} is that size against the address's own byte limit, and reads {@code 0} when there is
 * no such limit. A 0 is therefore not a measurement; only a positive value proves a limit exists.</li>
 * <li>{@code paging} means "over its limit", not "writing pages": an address full under FAIL or DROP reports paging
 * with no pages at all. {@link #pages} is what says whether anything was paged.</li>
 * </ul>
 *
 * @param addressSize  estimated address memory in bytes
 * @param limitPercent the broker's {@code addressLimitPercent}; see above for why 0 means nothing
 * @param pages        page files written for this address
 */
public record AddressOverview(String name, String routingTypes, long messageCount, Reading<Long> addressSize,
        long routedMessageCount, long unroutedMessageCount, boolean paging, boolean internal, boolean temporary,
        List<QueueOverview> queues, Reading<Long> limitPercent, Reading<Long> pages)
{

    /** Near a limit from here: the same 80% the broker-wide memory check uses. */
    public static final long NEAR_LIMIT_PERCENT = 80;

    /** Known counters only, as tests and older callers build one; the storage detail was not collected. */
    public AddressOverview(String name, String routingTypes, long messageCount, long addressSizeBytes,
            long routedMessageCount, long unroutedMessageCount, boolean paging, boolean internal, boolean temporary,
            List<QueueOverview> queues)
    {
        this(name, routingTypes, messageCount, Reading.of(addressSizeBytes), routedMessageCount, unroutedMessageCount,
                paging, internal, temporary, queues, Reading.notCollected("not in this listing"),
                Reading.notCollected("not in this listing"));
    }

    /** The same address with the storage figures given — for tests. */
    public AddressOverview withStorage(long limitPercent, long pages)
    {
        return new AddressOverview(name, routingTypes, messageCount, addressSize, routedMessageCount,
                unroutedMessageCount, paging, internal, temporary, queues, Reading.of(limitPercent), Reading.of(pages));
    }

    public boolean multicast()
    {
        return routingTypes != null && routingTypes.contains("MULTICAST");
    }

    /** Messages that reached the address but matched no queue — usually a misconfiguration. */
    public boolean hasUnrouted()
    {
        return unroutedMessageCount > 0;
    }

    /** The limit percentage, only when it proves a byte limit exists — the broker's 0 also means "no limit". */
    public Long measuredLimitPercent()
    {
        return limitPercent.available() && limitPercent.value() > 0 ? limitPercent.value() : null;
    }

    /** Page files on disk: paging in the ordinary sense, which under PAGE is how Artemis is meant to cope. */
    public boolean pagedToDisk()
    {
        return pages.available() && pages.value() > 0;
    }

    /**
     * Over its limit without writing pages — how a full address looks under FAIL or DROP, which refuse or discard
     * instead of paging. Unknown without the page count, so false then.
     */
    public boolean fullWithoutPaging()
    {
        return paging && pages.available() && pages.value() == 0;
    }

    /** At or past its byte limit by the broker's own percentage — which under BLOCK can read well over 100. */
    public boolean atByteLimit()
    {
        Long percent = measuredLimitPercent();
        return percent != null && percent >= 100;
    }

    public boolean nearByteLimit()
    {
        Long percent = measuredLimitPercent();
        return percent != null && percent >= NEAR_LIMIT_PERCENT && percent < 100;
    }

    /**
     * Worth a second look on the listing alone: over a limit other than by paging, or close to one. An address writing
     * pages is left out — only PAGE writes them, and there being over the limit is the policy working.
     */
    public boolean underPressure()
    {
        return !pagedToDisk() && (fullWithoutPaging() || atByteLimit() || nearByteLimit());
    }

    /** "21.2 KB", or null when the size was not read. */
    public String sizeText()
    {
        return addressSize.available() ? bytesText(addressSize.value()) : null;
    }

    /** Bytes in the largest unit that keeps a whole number in front: "512 B", "21.2 KB", "1.0 GB". */
    public static String bytesText(long bytes)
    {
        if (bytes < 1024)
        {
            return bytes + " B";
        }
        String[] units =
        { "KB", "MB", "GB", "TB"
        };
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1)
        {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
