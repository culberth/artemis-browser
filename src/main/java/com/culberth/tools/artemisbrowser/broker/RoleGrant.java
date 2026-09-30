package com.culberth.tools.artemisbrowser.broker;

import java.util.List;
import java.util.Map;

/**
 * One role's permissions on an address, as the broker reports them from the security setting that matches it.
 *
 * <p>
 * From {@code getRolesAsJSON(address)}, recorded on 2.55.0 and 2.57.0: one object per role, a boolean per permission. A
 * permission the broker did not report — an older version without {@code view} and {@code edit}, say — is absent from
 * {@code permissions}, not false.
 *
 * <p>
 * A role is not a user. Which users hold which roles lives in the broker's login module (a properties file, LDAP, a
 * certificate mapping), which management does not expose, so a role grant here is never proof of what a particular
 * client may do.
 */
public record RoleGrant(String role, Map<String, Boolean> permissions)
{

    /** A permission as the broker names it, what to call it, and what it allows. */
    public record Permission(String key, String label, String meaning)
    {
    }

    /** In the order a page shows them: messaging first, then queues and addresses, then management. */
    public static final List<Permission> PERMISSIONS = List.of(
            new Permission("send", "send", "send messages to the address"),
            new Permission("consume", "consume", "receive and acknowledge messages from its queues"),
            new Permission("browse", "browse", "read messages from its queues without removing them"),
            new Permission("createDurableQueue", "create durable queue",
                    "create a durable queue on it — a durable subscription makes one"),
            new Permission("deleteDurableQueue", "delete durable queue",
                    "delete a durable queue on it — unsubscribing does"),
            new Permission("createNonDurableQueue", "create non-durable queue",
                    "create a temporary or non-durable queue — a plain topic subscriber makes one"),
            new Permission("deleteNonDurableQueue", "delete non-durable queue", "delete one"),
            new Permission("createAddress", "create address", "create the address if it does not exist"),
            new Permission("deleteAddress", "delete address", "delete the address"),
            new Permission("manage", "manage",
                    "send management requests; it matters on the management address, not on this one"),
            new Permission("view", "view",
                    "read-only management operations, checked only when the broker enables management RBAC"),
            new Permission("edit", "edit",
                    "changing management operations, checked only when the broker enables management RBAC"));

    /** True, false, or null when the broker did not report this permission. */
    public Boolean allows(String key)
    {
        return permissions.get(key);
    }

    public boolean reported(String key)
    {
        return permissions.containsKey(key);
    }
}
