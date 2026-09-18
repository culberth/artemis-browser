package com.culberth.tools.artemisbrowser.broker;

/**
 * Connection details as typed on the connect form.
 *
 * <p>This carries the password, so it is deliberately short-lived: it exists between the form
 * submit and {@link BrokerSession#connect}, and is not retained afterwards. What survives in the
 * HTTP session is {@link ConnectionInfo}, which has no password field at all.
 */
public record BrokerCredentials(String host, int port, String username, String password) {

    public String brokerUrl() {
        return "tcp://" + host + ":" + port;
    }

    public ConnectionInfo toInfo() {
        return new ConnectionInfo(host, port, username);
    }
}
