package com.raphael.beachcleanup.config;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;

@Configuration
public class JwtConfig {
    public static final String ISSUER = "https://beachcleanup.example";
    public static final String AUDIENCE = "beachcleanup-api";
    public static final long TOKEN_LIFETIME_SECONDS = 1800;
    public static final String CREDENTIAL_VERSION_CLAIM = "credentialVersion";

    public static Long credentialVersion(Jwt jwt) {
        Object value = jwt.getClaim(CREDENTIAL_VERSION_CLAIM);
        if (value instanceof Long version) {
            return version;
        }
        return value instanceof Integer version ? version.longValue() : null;
    }

    @Bean
    public SecretKey jwtSigningKey(@Value("${security.jwt.secret:}") String secret, Environment environment) {
        byte[] bytes;
        if (secret.isBlank() && environment.acceptsProfiles(Profiles.of("test"))) {
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
        } else {
            try {
                bytes = Base64.getDecoder().decode(secret);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("JWT_SECRET deve ser uma chave em Base64.");
            }
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("Configure JWT_SECRET com pelo menos 32 bytes aleatórios em Base64.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
            .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(ISSUER), jwt -> {
                if (jwt.getExpiresAt() == null || jwt.getAudience() == null
                        || !jwt.getAudience().contains(AUDIENCE)) {
                    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                }
                return OAuth2TokenValidatorResult.success();
            }));
        return decoder;
    }
}
