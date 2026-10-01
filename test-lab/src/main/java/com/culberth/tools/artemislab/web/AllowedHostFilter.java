package com.culberth.tools.artemislab.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request whose {@code Host} header is not loopback.
 *
 * <p>
 * Binding 127.0.0.1 does not stop DNS rebinding: a web page can point a hostname it controls at this address and have
 * the browser drive the lab — which, unlike Artemis Browser, can create and fill queues. The browser sends the
 * attacker's hostname in {@code Host}, which is what checking it catches. Copied from Artemis Browser's filter rather
 * than shared, so the two artifacts stay independent; the lab has no allowed-hosts list because it serves loopback
 * only.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AllowedHostFilter extends OncePerRequestFilter
{

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        if (!isLoopbackHost(request.getHeader("Host")))
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "The regression lab serves loopback hosts only.");
            return;
        }
        chain.doFilter(request, response);
    }

    public static boolean isLoopbackHost(String host)
    {
        if (host == null || host.isBlank())
        {
            return false;
        }
        String hostname = stripPort(host.trim().toLowerCase(Locale.ROOT));
        if (hostname.isEmpty())
        {
            return false;
        }
        return hostname.equals("localhost") || isIpv6Loopback(hostname) || isIpv4Loopback(hostname);
    }

    private static String stripPort(String host)
    {
        if (host.startsWith("["))
        {
            int close = host.indexOf(']');
            return close < 0 ? host : host.substring(0, close + 1);
        }
        // More than one colon unbracketed is an IPv6 literal, not host:port.
        if (host.indexOf(':') != host.lastIndexOf(':'))
        {
            return host;
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private static boolean isIpv6Loopback(String hostname)
    {
        String bare = hostname.startsWith("[") && hostname.endsWith("]") ? hostname.substring(1, hostname.length() - 1)
                : hostname;
        return bare.equals("::1") || bare.equals("0:0:0:0:0:0:0:1");
    }

    /** Every octet parsed: a prefix match would accept {@code 127.0.0.1.attacker.example}. */
    private static boolean isIpv4Loopback(String hostname)
    {
        String[] octets = hostname.split("\\.", -1);
        if (octets.length != 4)
        {
            return false;
        }
        for (String octet : octets)
        {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)
                    || Integer.parseInt(octet) > 255)
            {
                return false;
            }
        }
        return Integer.parseInt(octets[0]) == 127;
    }
}
