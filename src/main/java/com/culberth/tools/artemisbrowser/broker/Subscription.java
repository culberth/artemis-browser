package com.culberth.tools.artemisbrowser.broker;

/**
 * One queue bound to an address, described as the subscription it is.
 *
 * <p>
 * Under multicast every subscriber gets its own queue, and the broker names that queue after how the subscription was
 * made: {@code clientId.subName} for a durable one, bare {@code subName} for a shared durable one made without a client
 * id, {@code nonDurable.clientId.subName} for a shared non-durable one, and a UUID for a plain non-durable one. Only
 * the last two can be told apart by flags. So {@link #kind} is read from flags and prefix, and {@link #clientIdHint} /
 * {@link #subscriptionHint} are split from the name — a guess the page labels as one, since a broker-configured queue
 * or another protocol's subscription can have a dot in its name for reasons of its own.
 *
 * @param filter the queue's filter in Artemis core syntax, or empty when it takes everything. A JMS selector given at
 *               subscribe time is stored already translated — {@code JMSPriority > 3} comes back as
 *               {@code AMQPriority > 3}.
 */
public record Subscription(String name, String address, String routingType, Kind kind, String clientIdHint,
        String subscriptionHint, String filter, String user, boolean durable, boolean temporary, boolean exclusive,
        long messageCount, long deliveringCount, long scheduledCount, int consumerCount, long messagesAdded,
        long messagesAcked, long messagesExpired, long messagesKilled, QueueBehavior behavior)
{

    public Subscription(String name, String address, String routingType, Kind kind, String clientIdHint,
            String subscriptionHint, String filter, String user, boolean durable, boolean temporary, boolean exclusive,
            long messageCount, long deliveringCount, long scheduledCount, int consumerCount, long messagesAdded,
            long messagesAcked, long messagesExpired, long messagesKilled)
    {
        this(name, address, routingType, kind, clientIdHint, subscriptionHint, filter, user, durable, temporary,
                exclusive, messageCount, deliveringCount, scheduledCount, consumerCount, messagesAdded, messagesAcked,
                messagesExpired, messagesKilled, QueueBehavior.NOT_COLLECTED);
    }

    /** The same subscription with its queue's configuration, from the listing row it was built from. */
    public Subscription withBehavior(QueueBehavior behavior)
    {
        return new Subscription(name, address, routingType, kind, clientIdHint, subscriptionHint, filter, user, durable,
                temporary, exclusive, messageCount, deliveringCount, scheduledCount, consumerCount, messagesAdded,
                messagesAcked, messagesExpired, messagesKilled, behavior);
    }

    private static final String SHARED_NON_DURABLE_PREFIX = "nonDurable.";

    public enum Kind
    {
        QUEUE("anycast queue"), DURABLE("durable subscription"), NON_DURABLE("non-durable subscription"),
        SHARED_NON_DURABLE("shared non-durable subscription");

        private final String label;

        Kind(String label)
        {
            this.label = label;
        }

        public String label()
        {
            return label;
        }
    }

    /** Builds the record, working out the kind and the name hints from what {@code listQueues} reported. */
    public static Subscription of(String name, String address, String routingType, String filter, String user,
            boolean durable, boolean temporary, boolean exclusive, long messageCount, long deliveringCount,
            long scheduledCount, int consumerCount, long messagesAdded, long messagesAcked, long messagesExpired,
            long messagesKilled)
    {
        Kind kind = classify(name, routingType, durable, temporary);
        String clientId = null;
        String subscription = null;
        if (kind == Kind.DURABLE || kind == Kind.SHARED_NON_DURABLE)
        {
            String rest = kind == Kind.SHARED_NON_DURABLE ? name.substring(SHARED_NON_DURABLE_PREFIX.length()) : name;
            int dot = rest.indexOf('.');
            clientId = dot > 0 ? rest.substring(0, dot) : null;
            subscription = dot > 0 ? rest.substring(dot + 1) : rest;
        }
        return new Subscription(name, address, routingType, kind, clientId, subscription,
                filter == null ? "" : filter.trim(), user, durable, temporary, exclusive, messageCount, deliveringCount,
                scheduledCount, consumerCount, messagesAdded, messagesAcked, messagesExpired, messagesKilled);
    }

    static Kind classify(String name, String routingType, boolean durable, boolean temporary)
    {
        if (!"MULTICAST".equalsIgnoreCase(routingType))
        {
            return Kind.QUEUE;
        }
        if (name.startsWith(SHARED_NON_DURABLE_PREFIX))
        {
            return Kind.SHARED_NON_DURABLE;
        }
        return durable && !temporary ? Kind.DURABLE : Kind.NON_DURABLE;
    }

    public boolean filtered()
    {
        return !filter.isEmpty();
    }

    /** Holding messages with nothing attached to take them. For a durable subscription, this is how a disk fills. */
    public boolean stalled()
    {
        return messageCount > 0 && consumerCount == 0;
    }

    /** How this queue must be named on the JMS read path — see {@link QueueStats#browseName()}. */
    public String browseName()
    {
        return address == null || address.equals(name) ? name : address + "::" + name;
    }
}
