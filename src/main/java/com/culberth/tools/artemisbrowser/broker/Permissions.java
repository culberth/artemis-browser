package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The roles the broker reports for some addresses, and whether it enforces security at all.
 *
 * <p>
 * Each address is its own reading. Artemis applies the security setting whose match is most specific for an address —
 * settings are not merged — and reports that one's roles without saying which match it was, so two addresses showing
 * the same roles may share a setting or have two identical ones.
 *
 * @param securityEnabled false means every connection may do everything, whatever the roles say
 * @param byAddress       roles per address, in the order asked
 * @param notRead         addresses past the limit, not asked about
 */
public record Permissions(Reading<Boolean> securityEnabled, Map<String, Reading<List<RoleGrant>>> byAddress,
        int notRead, Instant collectedAt)
{

    public Reading<List<RoleGrant>> of(String address)
    {
        Reading<List<RoleGrant>> roles = byAddress.get(address);
        return roles != null ? roles : Reading.notCollected("roles were not read for '" + address + "'");
    }

    /** False only when the broker said security is off; unknown counts as on, so roles are never dismissed by guess. */
    public boolean enforced()
    {
        return !securityEnabled.available() || Boolean.TRUE.equals(securityEnabled.value());
    }

    /** The roles granted this permission on the address; empty when none are, or the roles could not be read. */
    public List<String> rolesThatMay(String address, String permission)
    {
        return of(address).orElse(List.of()).stream().filter(grant -> Boolean.TRUE.equals(grant.allows(permission)))
                .map(RoleGrant::role).toList();
    }

    /** The permissions a table shows, as columns, in order. */
    public List<RoleGrant.Permission> columns()
    {
        return RoleGrant.PERMISSIONS;
    }

    /** "consumer, ops" or "no role" — for a cell that answers "who may". */
    public String rolesThatMayText(String address, String permission)
    {
        List<String> roles = rolesThatMay(address, permission);
        return roles.isEmpty() ? "no role" : String.join(", ", roles);
    }
}
