package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transaction and permission states for tests outside this package, each shaped like one recorded against 2.55.0 and
 * 2.57.0.
 */
public final class TransactionFixtures
{

    public static final String XID = "YnJhbmNoLTFndHJpZC1wNi54YaIQAAA=";

    private TransactionFixtures()
    {
    }

    /** Nothing prepared, nothing resolved by hand, every part read — the shape almost every broker has. */
    public static Transactions none()
    {
        return new Transactions(Reading.of(List.of()), 0, "", Reading.of(List.of()), Reading.of(List.of()), "",
                Instant.now());
    }

    /**
     * One branch prepared ten minutes ago that received one message from {@code source} and sent two to {@code target},
     * and one branch committed by hand — the probe's own shape.
     */
    public static Transactions held(String source, String target)
    {
        Instant now = Instant.now();
        return new Transactions(Reading.of(List.of(branch(source, target, now.minusSeconds(600)))), 1, "",
                Reading.of(List.of("YnJhbmNoLTFndHJpZC1wNi54YqIQAAA=")), Reading.of(List.of()),
                "Creation times are the broker's own, in its time zone (UTC, worked out from its connection"
                        + " listings); ages are from them.",
                now);
    }

    public static PreparedTransaction branch(String source, String target, Instant created)
    {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("orderId", "o-1");
        return new PreparedTransaction(XID, 4242, "gtrid-p6.xa", "branch-1", "9/30/26, 7:41:25 AM", created, "",
                List.of(new TransactionMessage(TransactionMessage.Operation.SEND, "(+) send", "TextMessage", target,
                        "2147486528", "ID:56adf19b-bca2-11f1-a295-00155d348692", 1790754085016L, true, 4, properties),
                        new TransactionMessage(TransactionMessage.Operation.SEND, "(+) send", "TextMessage", target,
                                "2147486529", "ID:56ae18ac-bca2-11f1-a295-00155d348692", 1790754085017L, true, 4,
                                Map.of()),
                        new TransactionMessage(TransactionMessage.Operation.RECEIVE, "(-) receive", "TextMessage",
                                source, "2147486514", "ID:56a761e5-bca2-11f1-a295-00155d348692", 1790754084973L, true,
                                4, Map.of())),
                3, true);
    }

    /** A user who may not list prepared transactions or the heuristic ones. */
    public static Transactions denied()
    {
        Reading<List<PreparedTransaction>> prepared = Reading.missing(Availability.DENIED,
                "AMQ229032: User: viewer does not have permission='VIEW' on address"
                        + " mops.broker.listPreparedTransactions");
        return new Transactions(prepared, 0, "", Reading.missing(Availability.DENIED, "AMQ229032"),
                Reading.missing(Availability.DENIED, "AMQ229032"), "", Instant.now());
    }

    /** The image's default roles on one address: {@code amq} may do everything but manage, view and edit. */
    public static Permissions defaultRoles(String address)
    {
        return new Permissions(Reading.of(true), Map.of(address, Reading.of(List.of(amq()))), 0, Instant.now());
    }

    public static RoleGrant amq()
    {
        Map<String, Boolean> permissions = new LinkedHashMap<>();
        for (RoleGrant.Permission permission : RoleGrant.PERMISSIONS)
        {
            permissions.put(permission.key(), !List.of("manage", "view", "edit").contains(permission.key()));
        }
        return new RoleGrant("amq", permissions);
    }

    public static Permissions deniedRoles(String address)
    {
        return new Permissions(Reading.of(true), Map.of(address, Reading.missing(Availability.DENIED,
                "AMQ229032: User: viewer does not have permission='VIEW' on address mops.broker.getRolesAsJSON")), 0,
                Instant.now());
    }
}
