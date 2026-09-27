package com.culberth.tools.artemisbrowser.broker;

import java.util.List;
import java.util.Set;

/**
 * Where messages sent to an address can go besides its own subscriptions: the dead-letter and expiry addresses its
 * settings name, and the diverts reading from it or writing to it.
 *
 * @param settings       null when they could not be read — the rest of the page does not depend on them
 * @param knownAddresses every address currently on the broker, to tell whether a named dead-letter or expiry address
 *                       exists. One that does not is where undeliverable messages go to be dropped.
 */
public record AddressRouting(AddressSettings settings, String settingsError, List<Divert> divertsFrom,
        List<Divert> divertsTo, Set<String> knownAddresses)
{

    public static final AddressRouting NONE = new AddressRouting(null, null, List.of(), List.of(), Set.of());

    public boolean exists(String address)
    {
        return address != null && knownAddresses.contains(address);
    }

    /** An exclusive divert out of this address: messages it matches never reach this address's subscribers. */
    public boolean hasExclusiveDivert()
    {
        return divertsFrom.stream().anyMatch(Divert::exclusive);
    }
}
