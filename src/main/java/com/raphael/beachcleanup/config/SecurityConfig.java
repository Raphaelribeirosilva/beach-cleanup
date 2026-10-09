package com.raphael.beachcleanup.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.security.SecurityErrorHandler;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    @Profile("test")
    @Order(1)
    public SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/h2-console", "/h2-console/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers.frameOptions(frames -> frames.sameOrigin()))
            .build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandler errors,
            UserRepository users) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/auth/login", "/users").permitAll()
                .requestMatchers(HttpMethod.GET, "/cleanupEvent", "/cleanupEvent/*").permitAll()
                .requestMatchers("/h2-console/**").denyAll()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(errors).accessDeniedHandler(errors)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                    long id;
                    try {
                        id = Long.parseLong(token.getSubject());
                    } catch (RuntimeException e) {
                        throw invalidToken();
                    }
                    var account = users.findById(id).orElseThrow(SecurityConfig::invalidToken);
                    Long credentialVersion = JwtConfig.credentialVersion(token);
                    if (credentialVersion == null || credentialVersion != account.getCredentialVersion()) {
                        throw invalidToken();
                    }
                    return new JwtAuthenticationToken(token,
                        List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())),
                        Long.toString(id));
                })));
        return http.build();
    }

    private static OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
    }
}
