package com.raphael.beachcleanup.config;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Only catalog reads are public; writes use the existing JWT chain and admin method security. */
@Configuration
public class PublicCatalogSecurityConfig {
    @Bean @Order(2)
    SecurityFilterChain publicCatalogSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher(request -> "GET".equals(request.getMethod())
                && request.getRequestURI().substring(request.getContextPath().length()).matches("/catalog/[^/]+"))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }
}
