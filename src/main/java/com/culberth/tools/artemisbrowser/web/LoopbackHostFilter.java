package com.culberth.tools.artemisbrowser.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request whose {@code Host} header is not a loopback host, on every path.
 *
 * <p>The connector is already bound to 127.0.0.1, but that alone does not make this app private:
 * a page on any website can point a hostname it controls at 127.0.0.1 and have the victim's own
 * browser drive this UI — DNS rebinding. Since this app holds a live, authenticated broker
 * connection and has no login of its own, that would hand a remote page a read of every queue.
 * Validating the Host header closes it, because the attacker's hostname is what the browser sends.
 *
 * <p>If this app is ever made reachable beyond the local machine, this filter is not the control to
 * relax — it is the thing standing in for authentication that would then have to be built.
 */
@Component
public class LoopbackHostFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!isLoopbackHost(request.getHeader("Host"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "artemis-browser only serves loopback hosts.");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String hostname = stripPort(host.trim().toLowerCase(Locale.ROOT));
        if (hostname.isEmpty()) {
            return false;
        }
        return hostname.equals("localhost") || isIpv6Loopback(hostname) || isIpv4Loopback(hostname);
    }

    private static String stripPort(String host) {
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            return close < 0 ? host : host.substring(0, close + 1);
        }
        // An unbracketed host with more than one colon is an IPv6 literal, not host:port — "::1"
        // must not be truncated at its first colon and then rejected as empty.
        if (host.indexOf(':') != host.lastIndexOf(':')) {
            return host;
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private static boolean isIpv6Loopback(String hostname) {
        String bare = hostname.startsWith("[") && hostname.endsWith("]")
                ? hostname.substring(1, hostname.length() - 1)
                : hostname;
        return bare.equals("::1") || bare.equals("0:0:0:0:0:0:0:1");
    }

    /**
     * True only for a literal address in 127.0.0.0/8.
     *
     * <p>Every octet is parsed rather than the string being prefix-matched. A {@code
     * startsWith("127.")} test accepts {@code 127.0.0.1.attacker.com} — a hostname the attacker
     * owns and can resolve wherever they like, which defeats the entire filter.
     */
    private static boolean isIpv4Loopback(String hostname) {
        String[] octets = hostname.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int i = 0; i < octet.length(); i++) {
                if (!Character.isDigit(octet.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return Integer.parseInt(octets[0]) == 127;
    }
}
