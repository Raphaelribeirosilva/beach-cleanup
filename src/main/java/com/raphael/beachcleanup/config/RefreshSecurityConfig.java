package com.raphael.beachcleanup.config;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** These JSON endpoints authenticate by the explicit opaque token, never by cookies or an expired JWT. */
@Configuration
public class RefreshSecurityConfig {
    @Bean @Order(4)
    SecurityFilterChain refreshSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/auth/refresh", "/auth/logout")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, "/auth/refresh", "/auth/logout").permitAll()
                .anyRequest().denyAll()).build();
    }
}
