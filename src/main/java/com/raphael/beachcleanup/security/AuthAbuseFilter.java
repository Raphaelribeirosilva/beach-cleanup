package com.raphael.beachcleanup.security;

import java.io.IOException;
import java.time.Clock;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import com.raphael.beachcleanup.exceptions.*;

@Component @Order(-120)
public class AuthAbuseFilter extends OncePerRequestFilter {
    private final AbuseLimiter limiter;
    private final ObjectMapper mapper;
    private final Clock clock;
    public AuthAbuseFilter(AbuseLimiter limiter, ObjectMapper mapper, Clock clock) { this.limiter = limiter; this.mapper = mapper; this.clock = clock; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = new org.springframework.web.util.UrlPathHelper().getPathWithinApplication(request);
        try {
            if ("POST".equals(request.getMethod())) {
                if (path.equals("/auth/login")) limiter.require("login-ip", request.getRemoteAddr(), 30, 900);
                else if (path.equals("/users")) limiter.require("signup-ip", request.getRemoteAddr(), 10, 3600);
                else if (java.util.Set.of("/auth/recovery", "/auth/verification").contains(path)) limiter.require("account-mail-ip", request.getRemoteAddr(), 20, 3600);
                else if (java.util.Set.of("/auth/reset-password", "/auth/verify-email").contains(path)) limiter.require("account-token-ip", request.getRemoteAddr(), 20, 900);
            }
            chain.doFilter(request, response);
        } catch (TooManyAttemptsException e) {
            response.setStatus(429); response.setContentType("application/json");
            response.setHeader("Retry-After", Long.toString(e.retryAfter)); response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getOutputStream(), new StandardError(clock.instant(), 429, "Too Many Requests", e.getMessage(), request.getRequestURI()));
        }
    }
}
