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

    private static String limit(String value, String unit)
    {
        if (value == null)
        {
            return null;
        }
        return value.trim().equals("-1") ? "unlimited" : value + unit;
    }
}
