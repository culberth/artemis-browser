package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * One client as the broker sees it: its connections, their sessions, and what those sessions consume and produce.
 *
 * <p>
 * A client is named by its client id when it set one. Many do not — plain CORE and AMQP clients often leave it unset,
 * and the broker reports {@code ""} — and then the only handle is the connection itself, so {@code clientId} is empty
 * and {@code connectionId} says which connection this is.
 *
 * @param connectionId the connection asked for when there is no client id, else null
 */
public record ClientView(String clientId, String connectionId, List<Connection> connections, List<Session> sessions,
        List<Consumer> consumers, List<Producer> producers)
{

    public boolean named()
    {
        return clientId != null && !clientId.isEmpty();
    }

    /** What to call it in a heading: the client id, or the connection's remote address. */
    public String title()
    {
        if (named())
        {
            return clientId;
        }
        return connections.isEmpty() ? connectionId : connections.get(0).remoteAddress();
    }

    /** Messages this client's consumers hold delivered and unacknowledged, across every queue. */
    public long inFlight()
    {
        return consumers.stream().mapToLong(Consumer::inTransit).sum();
    }

    /** "consume", "send", or "consume and send" — what this client does on an address. */
    public String uses(String address)
    {
        boolean consumes = consumers.stream().anyMatch(consumer -> address.equals(consumer.address()));
        boolean sends = producers.stream().anyMatch(producer -> address.equals(producer.address()));
        return consumes && sends ? "consume and send" : consumes ? "consume" : sends ? "send" : "";
    }

    /** @param createdText as the broker wrote it — {@code listConnections} gives a date string, not a number */
    public record Connection(String connectionId, String remoteAddress, String user, String protocol,
            String createdText, long sessionCount)
    {
    }

    public record Session(String sessionId, String connectionId, long consumerCount, long producerCount,
            String createdText)
    {
    }

    /**
     * @param inTransit delivered to this consumer and not yet acknowledged — its share of the queue's in-flight
     *                  messages
     */
    public record Consumer(String consumerId, String sessionId, String queue, String address, String filter,
            long delivered, long acknowledged, long inTransit)
    {
    }

    public record Producer(String producerId, String sessionId, String address, long sent, long bytesSent,
            String createdText)
    {
    }
}
