package com.raphael.beachcleanup.config;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class AccountRecoverySecurityConfig {
    @Bean @Order(5)
    SecurityFilterChain accountRecoverySecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/auth/recovery", "/auth/verification", "/auth/reset-password", "/auth/verify-email")
            .csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.POST, "/auth/recovery", "/auth/verification", "/auth/reset-password", "/auth/verify-email").permitAll().anyRequest().denyAll()).build();
    }
}
