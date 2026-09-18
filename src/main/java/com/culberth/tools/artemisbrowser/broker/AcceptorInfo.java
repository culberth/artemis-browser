package com.culberth.tools.artemisbrowser.broker;

/** One configured acceptor: what protocols the broker speaks, and where. */
public record AcceptorInfo(String name, String protocols, String host, int port)
{
}
