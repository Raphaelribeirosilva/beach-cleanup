package com.raphael.beachcleanup.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Public static entry points only; business routes keep their existing security. */
@Configuration
public class VolunteerPortalSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain volunteerPortalSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/", "/index.html", "/portal.css", "/portal.js", "/portal-api.js", "/portal-admin.js", "/portal-ui.js", "/portal-reports.js", "/portal-admin.css", "/portal-account.css", "/portal-locations.js", "/locations.json")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/portal.css", "/portal.js", "/portal-api.js", "/portal-admin.js", "/portal-ui.js", "/portal-reports.js", "/portal-admin.css", "/portal-account.css", "/portal-locations.js", "/locations.json").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/", "/index.html", "/portal.css", "/portal.js", "/portal-api.js", "/portal-admin.js", "/portal-ui.js", "/portal-reports.js", "/portal-admin.css", "/portal-account.css", "/portal-locations.js", "/locations.json").permitAll()
                .anyRequest().denyAll())
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
                + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")))
            .build();
    }
}
