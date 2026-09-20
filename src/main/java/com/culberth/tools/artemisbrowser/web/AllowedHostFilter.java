package com.culberth.tools.artemisbrowser.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request whose {@code Host} header is not one this app expects to be reached by.
 *
 * <p>
 * Binding an address is not the same as controlling who reaches it: a page on any website can point a hostname it
 * controls at this app's address and have the victim's own browser drive the UI — DNS rebinding. The browser sends the
 * attacker's hostname in {@code Host}, which is what makes checking it work. Since this app holds a live, authenticated
 * broker connection, that would otherwise hand a remote page a read of every queue.
 *
 * <p>
 * Loopback hosts are always allowed. Anything else has to be named in {@code artemis.allowed-hosts}, which is what a
 * deployment on a jump host sets to its own hostname. Empty means loopback only, which is the default and what every
 * install had before Phase 7.
 *
 * <p>
 * This runs ahead of authentication on purpose. A rebinding attack rides a session that is already signed in, so
 * checking the host only after the login has been accepted would be checking it too late.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AllowedHostFilter extends OncePerRequestFilter
{

    private final Set<String> allowedHosts;

    public AllowedHostFilter(@Value("${artemis.allowed-hosts:}") List<String> allowedHosts)
    {
        this.allowedHosts = allowedHosts.stream().map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        if (!isAllowed(request.getHeader("Host")))
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "artemis-browser does not serve that host. Add it to artemis.allowed-hosts if it is expected.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Loopback always, plus whatever the deployment named. */
    boolean isAllowed(String host)
    {
        if (isLoopbackHost(host))
        {
            return true;
        }
        if (host == null || allowedHosts.isEmpty())
        {
            return false;
        }
        return allowedHosts.contains(stripPort(host.trim().toLowerCase(Locale.ROOT)));
    }

    static boolean isLoopbackHost(String host)
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
        // An unbracketed host with more than one colon is an IPv6 literal, not host:port — "::1"
        // must not be truncated at its first colon and then rejected as empty.
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

    /**
     * True only for a literal address in 127.0.0.0/8.
     *
     * <p>
     * Every octet is parsed rather than the string being prefix-matched. A {@code
     * startsWith("127.")} test accepts {@code 127.0.0.1.attacker.com} — a hostname the attacker owns and can resolve
     * wherever they like, which defeats the entire filter.
     */
    private static boolean isIpv4Loopback(String hostname)
    {
        String[] octets = hostname.split("\\.", -1);
        if (octets.length != 4)
        {
            return false;
        }
        for (String octet : octets)
        {
            if (octet.isEmpty() || octet.length() > 3)
            {
                return false;
            }
            for (int i = 0; i < octet.length(); i++)
            {
                if (!Character.isDigit(octet.charAt(i)))
                {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255)
            {
                return false;
            }
        }
        return Integer.parseInt(octets[0]) == 127;
    }
}
