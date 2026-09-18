package com.culberth.tools.artemisbrowser.broker;

/** The part of the connection details that is safe to keep and to render: no password. */
public record ConnectionInfo(String host, int port, String username) {

    public String describe() {
        return username + "@" + host + ":" + port;
    }
}
