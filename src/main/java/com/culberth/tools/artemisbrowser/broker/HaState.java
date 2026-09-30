package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.Locale;

/**
 * This broker's high-availability role, as it reports it: its HA policy, whether it is active or a backup, and whether
 * a replica is synchronized with it.
 *
 * <p>
 * Every field is its own attribute read, so one the broker will not give leaves the others standing. What applies
 * depends on the policy: replica synchronization means something only under replication, and a broker whose policy is
 * {@code Primary Only} has no HA relationship at all — which is shown as not applicable, never as a failed sync. Policy
 * strings as recorded on 2.55.0 and 2.57.0: {@code Primary Only}, {@code Replication Primary w/quorum voting},
 * {@code Replication Backup w/quorum voting}. The shared-store ones were not observed and are recognised by name only.
 *
 * @param replicaSync true once a replica has finished its initial synchronization with this primary; on a replication
 *                    primary it went false within seconds of its backup stopping
 */
public record HaState(Reading<String> policy, Reading<String> nodeId, Reading<Boolean> active, Reading<Boolean> backup,
        Reading<Boolean> replicaSync, Reading<Boolean> sharedStore, Reading<Boolean> clustered,
        Reading<Long> pendingMirrorAcks, Instant collectedAt)
{

    /** What the policy says this broker is for. */
    public enum Kind
    {
        /** {@code Primary Only}: no backup relationship configured. */
        STANDALONE, REPLICATION_PRIMARY, REPLICATION_BACKUP, SHARED_STORE_PRIMARY, SHARED_STORE_BACKUP,
        /** A policy string this tool does not recognise; shown as the broker wrote it. */
        OTHER,
        /** The policy could not be read. */
        UNKNOWN
    }

    public Kind kind()
    {
        if (!policy.available() || policy.value() == null)
        {
            return Kind.UNKNOWN;
        }
        String text = policy.value().toLowerCase(Locale.ROOT);
        boolean backupPolicy = text.contains("backup");
        if (text.contains("replica"))
        {
            return backupPolicy ? Kind.REPLICATION_BACKUP : Kind.REPLICATION_PRIMARY;
        }
        if (text.contains("shared"))
        {
            return backupPolicy ? Kind.SHARED_STORE_BACKUP : Kind.SHARED_STORE_PRIMARY;
        }
        if (text.contains("primary only") || text.contains("live only"))
        {
            return Kind.STANDALONE;
        }
        return Kind.OTHER;
    }

    public boolean replication()
    {
        return kind() == Kind.REPLICATION_PRIMARY || kind() == Kind.REPLICATION_BACKUP;
    }

    /** The role in a few words, for a badge. */
    public String role()
    {
        return switch (kind())
        {
            case STANDALONE -> "standalone";
            case REPLICATION_PRIMARY -> "replication primary";
            case REPLICATION_BACKUP -> "replication backup";
            case SHARED_STORE_PRIMARY -> "shared-store primary";
            case SHARED_STORE_BACKUP -> "shared-store backup";
            case OTHER -> policy.value();
            case UNKNOWN -> "role unknown";
        };
    }

    /** True for a replication primary that reports its replica not synchronized — the one HA fault this can see. */
    public boolean replicaOutOfSync()
    {
        return kind() == Kind.REPLICATION_PRIMARY && replicaSync.available() && !replicaSync.value();
    }

    /** Why replica synchronization does not apply, or null when it does. */
    public String replicaSyncNotApplicable()
    {
        return switch (kind())
        {
            case STANDALONE -> "not applicable: no HA policy is configured";
            case SHARED_STORE_PRIMARY, SHARED_STORE_BACKUP ->
                "not applicable: a shared-store pair shares its journal rather than replicating it";
            case UNKNOWN -> "unknown: the HA policy could not be read";
            default -> null;
        };
    }
}
