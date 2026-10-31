package com.wellnessz.gateway.web;

import java.io.IOException;
import java.net.InetAddress;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Defence in depth for the local status page and actuator: the web server is bound to 127.0.0.1
 * ({@code server.address}); this filter additionally refuses any request whose peer is not a loopback address and sets
 * restrictive response headers. Device traffic never reaches this port.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoopbackOnlyFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isLoopback(request.getRemoteAddr())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(request, response);
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        // only literal addresses are accepted (no name resolution of the peer)
        if (!address.matches("[0-9a-fA-F:.%]+")) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }
}
