package com.culberth.tools.artemisbrowser.broker;

/**
 * A divert: a rule on the broker that copies — or, when {@link #exclusive}, takes — messages sent to one address and
 * sends them to another.
 *
 * <p>
 * The exclusive kind is the one worth knowing about. It takes the message instead of letting it route to the source
 * address's own queues, so a subscriber on that address simply never sees it, and nothing anywhere reports an error.
 *
 * @param filter      core syntax, or empty when it takes every message
 * @param routingType {@code PASS} keeps the message's routing type, {@code STRIP} clears it, {@code ANYCAST} and
 *                    {@code MULTICAST} set it
 */
public record Divert(String name, String address, String forwardingAddress, String filter, boolean exclusive,
        String routingType, String transformerClassName)
{

    public boolean filtered()
    {
        return filter != null && !filter.isBlank();
    }

    public boolean transformed()
    {
        return transformerClassName != null && !transformerClassName.isBlank();
    }
}
