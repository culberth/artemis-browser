package com.culberth.tools.artemisbrowser.broker;

import com.culberth.tools.artemisbrowser.broker.AddressSettings.FullPolicy;
import com.culberth.tools.artemisbrowser.broker.AddressSettings.Limit;
import java.util.Locale;

/**
 * How close one address is to its limits, what its settings say happens there, and whether an operator has blocked it —
 * usage beside policy, so "paging" can be told apart from "refusing sends".
 *
 * <p>
 * Three different measures, which must not stand in for one another:
 * <ul>
 * <li><b>address memory</b> — {@code addressSize}, the broker's in-memory estimate of what the address holds, which is
 * what {@code max-size-bytes} and {@code global-max-size} are compared with;</li>
 * <li><b>persistent size</b> — journal bytes, shown per queue elsewhere;</li>
 * <li><b>disk use</b> — the whole store's filesystem, on the broker page.</li>
 * </ul>
 *
 * <p>
 * Measured on 2.55.0 and 2.57.0 against 20KB limits: under PAGE an address sat at 108% writing pages and lost nothing;
 * under FAIL and DROP it sat at 108% reporting {@code paging} with no pages while refusing or discarding; under BLOCK
 * it reached 418% — producer credit is granted ahead — with {@code paging} false and its sender stalled. So the policy
 * is what turns a percentage into a consequence, and the {@code paging} flag alone says neither.
 *
 * @param settings the address's resolved settings, or why they could not be read
 * @param blocked  {@code blockedViaManagement} — an operator's {@code block()}, which the listing does not carry
 * @param health   the broker's global memory figures, for context; null when not read
 */
public record AddressPressure(AddressOverview address, Reading<AddressSettings> settings, Reading<Boolean> blocked,
        BrokerHealth health)
{

    public enum State
    {
        /** Nothing near a limit that costs anyone anything. */
        NORMAL,
        /** Over its limit under PAGE and writing pages: the policy working, not a fault. */
        PAGING,
        /** Within {@link AddressOverview#NEAR_LIMIT_PERCENT} of a limit whose policy blocks, fails or drops. */
        NEAR_LIMIT,
        /** At a limit whose policy blocks, fails or drops. */
        AT_LIMIT,
        /** Paging with a page limit set, and near or past it. */
        NEAR_PAGE_LIMIT,
        /** Blocked by an operator through management — observed, not inferred. */
        BLOCKED
    }

    public State state()
    {
        if (blocked.available() && Boolean.TRUE.equals(blocked.value()))
        {
            return State.BLOCKED;
        }
        FullPolicy policy = policy();
        if (policy != null && policy.harmful())
        {
            if (atLimit())
            {
                return State.AT_LIMIT;
            }
            Long percent = utilization().orElse(null);
            if (percent != null && percent >= AddressOverview.NEAR_LIMIT_PERCENT)
            {
                return State.NEAR_LIMIT;
            }
            return State.NORMAL;
        }
        Long pagePercent = pageLimitUtilization();
        if (pagePercent != null && pagePercent >= AddressOverview.NEAR_LIMIT_PERCENT)
        {
            return State.NEAR_PAGE_LIMIT;
        }
        return address.paging() || address.pagedToDisk() ? State.PAGING : State.NORMAL;
    }

    /** The full policy from the settings, or null when they were not read or did not say. */
    public FullPolicy policy()
    {
        return settings.available() ? settings.value().addressFullPolicy() : null;
    }

    public FullPolicy pagePolicy()
    {
        return settings.available() ? settings.value().pageFullPolicy() : null;
    }

    public Limit byteLimit()
    {
        return settings.available() ? settings.value().maxSizeBytesLimit() : null;
    }

    public Limit messageLimit()
    {
        return settings.available() ? settings.value().maxMessagesLimit() : null;
    }

    /**
     * How full the address is against its own limits, in percent: the larger of the byte and message measures that
     * apply. The byte measure is the broker's own {@code addressLimitPercent}; the message one is computed, because the
     * broker's percentage counts bytes only and reads 0 on an address full by message count. Not collected when the
     * address has no limit of its own — then only {@code global-max-size} bounds it, shown separately.
     */
    public Reading<Long> utilization()
    {
        if (!settings.available())
        {
            Long measured = address.measuredLimitPercent();
            return measured != null ? Reading.of(measured) : settings.absent();
        }
        Long bytes = bytePercent();
        Long messages = messagePercent();
        if (bytes == null && messages == null)
        {
            if (byteLimit().limited() && !address.limitPercent().available())
            {
                return address.limitPercent().absent();
            }
            return Reading.notCollected("no limit of its own; only global-max-size applies");
        }
        return Reading.of(Math.max(bytes == null ? 0 : bytes, messages == null ? 0 : messages));
    }

    private Long bytePercent()
    {
        Limit limit = byteLimit();
        if (limit == null || !limit.limited() || limit.value() == 0)
        {
            return null;
        }
        if (address.limitPercent().available())
        {
            return address.limitPercent().value();
        }
        return address.addressSize().available() ? address.addressSize().value() * 100 / limit.value() : null;
    }

    private Long messagePercent()
    {
        Limit limit = messageLimit();
        if (limit == null || !limit.limited() || limit.value() == 0)
        {
            return null;
        }
        return address.messageCount() * 100 / limit.value();
    }

    /**
     * At its limit: by percentage, or by the broker's own flag — which under FAIL and DROP is set, with no pages, once
     * the address is full. Under BLOCK that flag stays false, so the percentage is what shows it.
     */
    public boolean atLimit()
    {
        Long percent = utilization().orElse(null);
        return (percent != null && percent >= 100) || address.fullWithoutPaging();
    }

    /**
     * How near the page limit, in percent, or null when there is none or nothing is paged. Approximate, and on the high
     * side: page bytes are estimated as pages times the page size, and paged messages are bounded by the address's
     * message count, since nothing reports the paged count itself.
     */
    public Long pageLimitUtilization()
    {
        if (!settings.available() || !address.pagedToDisk())
        {
            return null;
        }
        AddressSettings values = settings.value();
        Long result = null;
        Limit bytes = values.pageLimitBytes();
        Limit pageSize = Limit.of(values.get("pageSizeBytes"));
        if (bytes.limited() && bytes.value() > 0 && pageSize.limited())
        {
            result = address.pages().value() * pageSize.value() * 100 / bytes.value();
        }
        Limit messages = values.pageLimitMessages();
        if (messages.limited() && messages.value() > 0)
        {
            long percent = address.messageCount() * 100 / messages.value();
            result = result == null ? percent : Math.max(result, percent);
        }
        return result;
    }

    /** True for a state an operator would want to hear about. */
    public boolean concerning()
    {
        State state = state();
        return state == State.BLOCKED || state == State.AT_LIMIT || state == State.NEAR_LIMIT
                || state == State.NEAR_PAGE_LIMIT;
    }

    /** "108%", or null when there is no percentage to give. */
    public String utilizationText()
    {
        Reading<Long> percent = utilization();
        return percent.available() ? percent.value() + "%" : null;
    }

    /** This address's share of the broker-wide limit, e.g. "0.02%", or null when that limit was not read. */
    public String globalShareText()
    {
        if (health == null || !health.globalMaxBytes().available() || health.globalMaxBytes().value() <= 0
                || !address.addressSize().available())
        {
            return null;
        }
        double share = 100.0 * address.addressSize().value() / health.globalMaxBytes().value();
        return String.format(Locale.ROOT, "%.2f%%", share);
    }

    /** "20000 bytes", "unlimited", "not set" — the limit as the settings table words it. */
    public static String limitText(Limit limit, String unit)
    {
        if (limit == null)
        {
            return null;
        }
        return switch (limit.state())
        {
            case NOT_SET -> "not set";
            case UNLIMITED -> "unlimited";
            case LIMITED -> limit.value() + unit;
        };
    }

    public String byteLimitText()
    {
        Limit limit = byteLimit();
        return limit != null && limit.limited() ? AddressOverview.bytesText(limit.value()) : limitText(limit, "");
    }

    public String messageLimitText()
    {
        return limitText(messageLimit(), " messages");
    }

    /** Both page limits in one phrase, e.g. "30 messages", or "none" when neither is set to a value. */
    public String pageLimitText()
    {
        if (!settings.available())
        {
            return null;
        }
        AddressSettings values = settings.value();
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (values.pageLimitBytes().limited())
        {
            parts.add(AddressOverview.bytesText(values.pageLimitBytes().value()));
        }
        if (values.pageLimitMessages().limited())
        {
            parts.add(values.pageLimitMessages().value() + " messages");
        }
        return parts.isEmpty() ? "none" : String.join(" or ", parts);
    }

    /** Broker-wide address memory against global-max-size, e.g. "166.6 KB of 1.0 GB", or null when not read. */
    public String globalText()
    {
        if (health == null || !health.memoryUsedBytes().available())
        {
            return null;
        }
        String used = AddressOverview.bytesText(health.memoryUsedBytes().value());
        return health.globalMaxBytes().available() && health.globalMaxBytes().value() > 0
                ? used + " of " + AddressOverview.bytesText(health.globalMaxBytes().value())
                : used;
    }
}
