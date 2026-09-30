package com.culberth.tools.artemisbrowser.broker;

/**
 * A broker connection — an outbound AMQP link this broker opens to another, for mirroring, federation or sending and
 * receiving — from {@code listBrokerConnections}.
 *
 * <p>
 * The listing carries no traffic counters and does not say what the connection is for. A mirror is recognised by its
 * internal queue, {@code $ACTIVEMQ_ARTEMIS_MIRROR_<name>}, whose depth is the mirror's backlog. Connected means this
 * broker holds a connection; it does not mean the other side is healthy or caught up.
 *
 * @param uri the configured URI with any password masked ({@link Redaction#uri}); the broker returns it verbatim
 */
public record BrokerLink(String name, String protocol, String uri, boolean started, boolean connected)
{

    /** The internal queue a mirror connection of this name feeds from. */
    public String mirrorQueue()
    {
        return "$ACTIVEMQ_ARTEMIS_MIRROR_" + name;
    }
}
