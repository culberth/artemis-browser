package com.culberth.tools.artemisbrowser.broker;

import java.util.List;
import java.util.Map;

/**
 * A cluster connection, as this broker reports it.
 *
 * <p>
 * {@code peers} is the broker's {@code nodes} attribute: the nodes it is connected to now, by node id, never itself and
 * never a backup — on 2.55.0 it went empty when the one peer stopped. Messages bound for a peer wait in a
 * store-and-forward queue named {@code $.artemis.internal.sf.<name>.<peer node id>}, which is where a backlog to that
 * peer shows.
 *
 * @param peers            connected node id to the {@code host/ip:port} the broker gives for it
 * @param staticConnectors connector names the connection was configured with; empty when it uses discovery
 * @param acknowledged     messages the peers acknowledged, cumulative
 * @param sent             the broker's {@code messagesPendingAcknowledgement}, which is cumulative sent — see
 *                         {@link Bridge}
 */
public record ClusterLink(String name, boolean started, Map<String, String> peers, long maxHops, String loadBalancing,
        List<String> staticConnectors, String discoveryGroup, long acknowledged, long sent)
{

    /** Prefix of this cluster connection's store-and-forward queues, one per peer. */
    public String forwardQueuePrefix()
    {
        return "$.artemis.internal.sf." + name + ".";
    }

    public boolean discovery()
    {
        return discoveryGroup != null && !discoveryGroup.isBlank();
    }
}
