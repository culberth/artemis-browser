package com.culberth.tools.artemisbrowser.broker;

/**
 * A connector this broker is configured with — where a bridge or cluster connection naming it will try to connect.
 *
 * <p>
 * Only name, host and port are kept. {@code connectorsAsJSON} returns a connector's {@code user} and {@code password}
 * in clear under {@code extraProps} — seen on 2.55.0 — and its {@code params} can hold key-store passwords, so nothing
 * else from it is ever read.
 */
public record Connector(String name, String host, String port)
{

    public String target()
    {
        return host + (port == null || port.isBlank() ? "" : ":" + port);
    }
}
