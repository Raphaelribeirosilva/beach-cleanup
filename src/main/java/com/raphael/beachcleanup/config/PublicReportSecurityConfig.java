package com.raphael.beachcleanup.config;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class PublicReportSecurityConfig {
    @Bean @Order(3)
    SecurityFilterChain publicReportSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/reports/impact")
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/reports/impact").permitAll()
                .anyRequest().denyAll()).build();
    }
}
