package com.culberth.tools.artemislab.broker;

import com.culberth.tools.artemislab.LabException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Whether a loopback port is free before a broker is published on it.
 *
 * <p>
 * Taken means refused, never "pick another": a lab that quietly moved its broker would leave Artemis Browser — or a
 * person following the procedure — connected to whatever already held the port. On this machine that is a real
 * possibility: 61616 is the kind cluster's broker and 62616 is the memory's hand-run test container.
 */
final class PortProbe
{

    private PortProbe()
    {
    }

    static void ensureFree(int port)
    {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (Socket socket = new Socket())
        {
            socket.connect(new InetSocketAddress(loopback, port), 300);
            throw taken(port, "something is listening there");
        }
        catch (IOException expected)
        {
            // Nothing answered: now check nothing holds it without listening.
        }
        try (ServerSocket server = new ServerSocket())
        {
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(loopback, port));
        }
        catch (IOException e)
        {
            throw taken(port, e.getMessage());
        }
    }

    private static LabException taken(int port, String why)
    {
        return new LabException("Port " + port + " on 127.0.0.1 is already in use (" + why
                + "). The lab will not publish its broker somewhere else; stop what holds it or set lab.broker.port.");
    }
}
