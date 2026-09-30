package com.culberth.tools.artemisbrowser.broker;

import java.util.Map;
import java.util.TreeMap;

/**
 * The address settings the broker resolved for one address — from its {@code address-setting} match, which is usually
 * {@code #}, so an address nobody configured still has settings.
 *
 * <p>
 * {@code getAddressSettingsAsJSON} is a flat object whose numbers are bare, unlike most management JSON here, and which
 * <b>leaves a key out</b> when the setting is at its default: on 2.44.0 {@code maxDeliveryAttempts} and
 * {@code retroactiveMessageCount} were simply absent. So an absent key reads as "not set", never as zero — zero
 * delivery attempts would be a very different broker.
 *
 * @param all every key the broker returned, as text, sorted — the ones this page does not single out are still worth
 *            seeing
 */
public record AddressSettings(Map<String, String> all)
{

    public static AddressSettings of(Map<String, String> values)
    {
        return new AddressSettings(new TreeMap<>(values));
    }

    /** The value, or null when the broker left the key out. */
    public String get(String key)
    {
        String value = all.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    public String deadLetterAddress()
    {
        return get("deadLetterAddress");
    }

    public String expiryAddress()
    {
        return get("expiryAddress");
    }

    public String fullPolicy()
    {
        return get("addressFullMessagePolicy");
    }

    /** "unlimited" for -1, which is what the broker reports when there is no size limit. */
    public String maxSize()
    {
        return limit(get("maxSizeBytes"), " bytes");
    }

    public String maxMessages()
    {
        return limit(get("maxSizeMessages"), " messages");
    }

    public String maxDeliveryAttempts()
    {
        return get("maxDeliveryAttempts");
    }

    public String redeliveryDelayMillis()
    {
        return get("redeliveryDelay");
    }

    public String retroactiveMessageCount()
    {
        return get("retroactiveMessageCount");
    }

    public boolean flag(String key)
    {
        return Boolean.parseBoolean(get(key));
    }

    /**
     * The {@code default*} settings the address gives queues created on it — present only when configured, verified on
     * 2.55.0 and 2.57.0. A default is what a new queue starts with, never what an existing one has: the queue listing
     * reports that.
     */
    public Map<String, String> queueDefaults()
    {
        Map<String, String> defaults = new java.util.LinkedHashMap<>();
        all.forEach((key, value) ->
        {
            if (key.startsWith("default") && value != null && !value.isBlank())
            {
                defaults.put(key, value);
            }
        });
        return defaults;
    }

    /**
     * {@code defaultNonDestructive}: the only place non-destructive can be read, and only as what new queues on this
     * address start with. Null when not set.
     */
    public Boolean nonDestructiveDefault()
    {
        String value = get("defaultNonDestructive");
        return value == null ? null : Boolean.parseBoolean(value);
    }

    /** The address's own byte limit — what the broker's {@code addressLimitPercent} is measured against. */
    public Limit maxSizeBytesLimit()
    {
        return Limit.of(get("maxSizeBytes"));
    }

    public Limit maxMessagesLimit()
    {
        return Limit.of(get("maxSizeMessages"));
    }

    public Limit pageLimitBytes()
    {
        return Limit.of(get("pageLimitBytes"));
    }

    public Limit pageLimitMessages()
    {
        return Limit.of(get("pageLimitMessages"));
    }

    /** What happens when the address reaches its limit; null when the broker left the key out. */
    public FullPolicy addressFullPolicy()
    {
        return FullPolicy.parse(fullPolicy());
    }

    /**
     * What happens when the paging store reaches its page limit. Absent unless configured — verified on 2.55.0 and
     * 2.57.0 — so null here, never a guessed default.
     */
    public FullPolicy pageFullPolicy()
    {
        return FullPolicy.parse(get("pageFullMessagePolicy"));
    }

    /** True when either page limit is set to a real value. */
    public boolean hasPageLimit()
    {
        return pageLimitBytes().limited() || pageLimitMessages().limited();
    }

    /**
     * A size or count limit as the broker states it. Three different things, which a single number would blur: the
     * broker left the key out ({@link #notSet()}), it said {@code -1} ({@link #unlimited()}), or it named a value.
     *
     * @param value the limit; meaningful only when {@link #limited()}
     */
    public record Limit(State state, long value)
    {

        public enum State
        {
            NOT_SET, UNLIMITED, LIMITED
        }

        static Limit of(String raw)
        {
            if (raw == null)
            {
                return new Limit(State.NOT_SET, 0);
            }
            try
            {
                long value = Long.parseLong(raw.trim());
                return value < 0 ? new Limit(State.UNLIMITED, 0) : new Limit(State.LIMITED, value);
            }
            catch (NumberFormatException e)
            {
                // Not a number the broker would send; say nothing rather than invent a limit.
                return new Limit(State.NOT_SET, 0);
            }
        }

        public boolean limited()
        {
            return state == State.LIMITED;
        }

        public boolean unlimited()
        {
            return state == State.UNLIMITED;
        }

        public boolean notSet()
        {
            return state == State.NOT_SET;
        }
    }

    /**
     * {@code addressFullMessagePolicy} and {@code pageFullMessagePolicy}, with what each means to whoever is sending.
     * Checked against 2.55.0 and 2.57.0: PAGE kept every message, FAIL answered the sender {@code AMQ229102 … is full},
     * DROP accepted the send and kept nothing, BLOCK left the sender waiting.
     */
    public enum FullPolicy
    {
        PAGE("further messages are paged to disk — normal, and nothing is lost"),
        BLOCK("producers are made to wait until consumers free up space"),
        FAIL("sends are rejected with an \"address is full\" error"),
        DROP("further messages are accepted and silently discarded");

        private final String consequence;

        FullPolicy(String consequence)
        {
            this.consequence = consequence;
        }

        public String consequence()
        {
            return consequence;
        }

        /** True for the policies under which a full address costs a sender something. */
        public boolean harmful()
        {
            return this != PAGE;
        }

        static FullPolicy parse(String raw)
        {
            if (raw == null)
            {
                return null;
            }
            try
            {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            }
            catch (IllegalArgumentException e)
            {
                return null;
            }
        }
    }

    private static String limit(String value, String unit)
    {
        if (value == null)
        {
            return null;
        }
        return value.trim().equals("-1") ? "unlimited" : value + unit;
    }
}
