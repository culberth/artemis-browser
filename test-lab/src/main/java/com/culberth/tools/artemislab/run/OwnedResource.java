package com.culberth.tools.artemislab.run;

/**
 * One broker resource a run owns, by exact name.
 *
 * <p>
 * A resource is recorded {@link State#PLANNED} only after the broker was checked and did not have it, and before the
 * call that creates it. So a PLANNED resource that exists after a crash was created by that call, and cleanup may
 * remove it; a name that already existed is never recorded as owned.
 *
 * @param kind        address or queue
 * @param name        exact name
 * @param address     the address a queue is bound to (the name itself for an address)
 * @param routingType ANYCAST or MULTICAST
 * @param state       where it is in its life
 * @param detail      the latest outcome, for the page
 */
public record OwnedResource(Kind kind, String name, String address, String routingType, State state, String detail)
{

    public enum Kind
    {
        ADDRESS, QUEUE
    }

    public enum State
    {
        PLANNED, CREATED, DELETED, DELETE_FAILED, NOT_CREATED, REMOVED_WITH_BROKER;

        /** Might still exist on the broker, so cleanup must look. */
        public boolean mayExist()
        {
            return this == PLANNED || this == CREATED || this == DELETE_FAILED;
        }
    }

    public OwnedResource with(State next, String text)
    {
        return new OwnedResource(kind, name, address, routingType, next, text);
    }
}
