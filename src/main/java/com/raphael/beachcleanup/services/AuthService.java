package com.raphael.beachcleanup.services;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.raphael.beachcleanup.config.JwtConfig;
import com.raphael.beachcleanup.dto.LoginDTO;
import com.raphael.beachcleanup.dto.TokenDTO;
import com.raphael.beachcleanup.dto.ChangePasswordDTO;
import com.raphael.beachcleanup.exceptions.FieldValidationException;
import com.raphael.beachcleanup.repositories.VolunteerRepository;

@Service
public class AuthService {
    private final VolunteerRepository volunteers;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String dummyHash;

    public AuthService(VolunteerRepository volunteers, PasswordEncoder passwords, JwtEncoder encoder) {
        this.volunteers = volunteers;
        this.passwords = passwords;
        this.encoder = encoder;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public TokenDTO login(LoginDTO input) {
        var volunteer = volunteers.findByEmail(VolunteerService.normalizeEmail(input.email()));
        String hash = volunteer.map(value -> value.getPassword()).orElse(dummyHash);
        boolean matches = input.password().getBytes(StandardCharsets.UTF_8).length <= 72
            && passwords.matches(input.password(), hash);
        if (!matches || volunteer.isEmpty()) {
            throw new BadCredentialsException("Credenciais inválidas");
        }
        var account = volunteer.orElseThrow();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(JwtConfig.ISSUER).audience(List.of(JwtConfig.AUDIENCE))
            .subject(account.getId().toString()).issuedAt(now).notBefore(now)
            .claim(JwtConfig.CREDENTIAL_VERSION_CLAIM, account.getCredentialVersion())
            .expiresAt(now.plusSeconds(JwtConfig.TOKEN_LIFETIME_SECONDS)).id(UUID.randomUUID().toString()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenDTO(token, "Bearer", JwtConfig.TOKEN_LIFETIME_SECONDS,
            account.getId(), account.getRole().name());
    }

    @Transactional
    public void changePassword(Jwt principal, ChangePasswordDTO input) {
        if (principal == null) {
            throw new BadCredentialsException("Credenciais inválidas");
        }
        long id = Long.parseLong(principal.getSubject());
        var account = volunteers.findById(id).orElseThrow(
            () -> new BadCredentialsException("Credenciais inválidas"));
        Long credentialVersion = JwtConfig.credentialVersion(principal);
        if (credentialVersion == null || credentialVersion != account.getCredentialVersion()
                || input.currentPassword() == null
                || input.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(input.currentPassword(), account.getPassword())) {
            throw new BadCredentialsException("Credenciais inválidas");
        }
        String newPassword = input.newPassword();
        if (newPassword == null || newPassword.isBlank()) {
            throw new FieldValidationException("newPassword", "A nova senha é obrigatória");
        }
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new FieldValidationException("newPassword", "A nova senha deve ter no máximo 72 bytes em UTF-8");
        }
        if (passwords.matches(newPassword, account.getPassword())) {
            throw new FieldValidationException("newPassword", "A nova senha deve ser diferente da senha atual");
        }
        account.setPassword(passwords.encode(newPassword));
        account.setCredentialVersion(Math.incrementExact(account.getCredentialVersion()));
        volunteers.saveAndFlush(account);
    }
}
