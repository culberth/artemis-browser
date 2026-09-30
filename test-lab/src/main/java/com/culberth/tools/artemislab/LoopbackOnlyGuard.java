package com.culberth.tools.artemislab;

import com.culberth.tools.artemislab.web.AllowedHostFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the lab bound anywhere but loopback.
 *
 * <p>
 * The lab has no login and can create, fill and remove queues. Artemis Browser earns a wider bind with a login and TLS;
 * the lab has neither, and until a shared deployment is deliberately designed it stays on this machine. An empty
 * {@code server.address} means every interface, so it is refused too.
 */
@Component
public class LoopbackOnlyGuard
{

    public LoopbackOnlyGuard(@Value("${server.address:}") String bindAddress)
    {
        if (!loopback(bindAddress))
        {
            throw new IllegalStateException(
                    "The regression lab binds to loopback only (server.address=127.0.0.1); got '" + bindAddress
                            + "'. It has no login and can change the brokers it owns.");
        }
    }

    static boolean loopback(String bindAddress)
    {
        return AllowedHostFilter.isLoopbackHost(bindAddress);
    }
}
