package com.raphael.beachcleanup.services;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
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
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.repositories.AuthSessionRepository;
import com.raphael.beachcleanup.repositories.RefreshTokenRepository;
import com.raphael.beachcleanup.entities.AuthSession;
import com.raphael.beachcleanup.entities.RefreshToken;
import com.raphael.beachcleanup.entities.User;

@Service
public class AuthService {
    public static final long REFRESH_LIFETIME_SECONDS = 7 * 24 * 60 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String dummyHash;
    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;
    private final com.raphael.beachcleanup.config.AccountPolicy policy;
    private final com.raphael.beachcleanup.security.AbuseLimiter limiter;
    private final com.raphael.beachcleanup.security.PasswordPolicy passwordPolicy;

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtEncoder encoder,
            AuthSessionRepository sessions, RefreshTokenRepository refreshTokens, Clock clock,
            com.raphael.beachcleanup.config.AccountPolicy policy,
            com.raphael.beachcleanup.security.AbuseLimiter limiter, com.raphael.beachcleanup.security.PasswordPolicy passwordPolicy) {
        this.users = users;
        this.passwords = passwords;
        this.encoder = encoder;
        this.sessions = sessions; this.refreshTokens = refreshTokens; this.clock = clock;
        this.policy = policy; this.limiter = limiter; this.passwordPolicy = passwordPolicy;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public TokenDTO login(LoginDTO input) {
        limiter.require("login-email", UserService.normalizeEmail(input.email()), 10, 900);
        var foundAccount = users.findIdByEmail(UserService.normalizeEmail(input.email()));
        // Serialize credential checks with password changes and refresh operations.
        var account = foundAccount.flatMap(users::findByIdForAuthentication).orElse(null);
        String hash = account == null ? dummyHash : account.getPassword();
        boolean matches = input.password().getBytes(StandardCharsets.UTF_8).length <= 72
            && passwords.matches(input.password(), hash);
        if (!matches || account == null || policy.verifiedRequired && !account.isEmailVerified()) {
            throw new BadCredentialsException("Credenciais inválidas");
        }
        Instant now = clock.instant();
        var session = sessions.save(new AuthSession(UUID.randomUUID().toString(), account,
            now.plusSeconds(REFRESH_LIFETIME_SECONDS)));
        return issue(account, session, now);
    }

    private TokenDTO issue(User account, AuthSession session, Instant now) {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(hash(refresh), session));
        long accessLifetime = Math.min(JwtConfig.TOKEN_LIFETIME_SECONDS, Duration.between(now, session.getExpiresAt()).getSeconds());
        var claims = JwtClaimsSet.builder().issuer(JwtConfig.ISSUER).audience(List.of(JwtConfig.AUDIENCE))
            .subject(account.getId().toString()).issuedAt(now).notBefore(now)
            .claim(JwtConfig.CREDENTIAL_VERSION_CLAIM, account.getCredentialVersion())
            .claim("sid", session.getId())
            .expiresAt(now.plusSeconds(accessLifetime)).id(UUID.randomUUID().toString()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenDTO(token, "Bearer", accessLifetime,
            account.getId(), account.getRole().name(), refresh, Duration.between(now, session.getExpiresAt()).getSeconds());
    }

    /** Replay revocation MUST commit even though the response is 401. */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public TokenDTO refresh(String refresh) {
        String hash = hash(refresh);
        var owner = refreshTokens.owner(hash).orElseThrow(AuthService::invalidRefresh);
        var user = users.findByIdForAuthentication(owner.getUserId()).orElseThrow(AuthService::invalidRefresh);
        var session = sessions.findLocked(owner.getSessionId()).orElseThrow(AuthService::invalidRefresh);
        // A locking read avoids stale snapshots after waiting on another rotation in MySQL REPEATABLE READ.
        var token = refreshTokens.findLocked(hash).orElseThrow(AuthService::invalidRefresh);
        Instant now = clock.instant();
        if (token.isConsumed() || !session.active(now) || session.getCredentialVersion() != user.getCredentialVersion()
                || policy.verifiedRequired && !user.isEmailVerified()
                || Duration.between(now, session.getExpiresAt()).getSeconds() < 1) {
            session.revoke(now);
            throw invalidRefresh();
        }
        token.consume();
        return issue(user, session, now);
    }

    @Transactional
    public void logout(String refresh) {
        var owner = refreshTokens.owner(hash(refresh)).orElse(null);
        if (owner == null || users.findByIdForAuthentication(owner.getUserId()).isEmpty()) return;
        sessions.findLocked(owner.getSessionId()).ifPresent(session -> session.revoke(clock.instant()));
    }

    private static BadCredentialsException invalidRefresh() { return new BadCredentialsException("Sessão encerrada ou refresh token inválido."); }
    private static String hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidRefresh();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Transactional
    public void changePassword(Jwt principal, ChangePasswordDTO input) {
        if (principal == null) {
            throw new BadCredentialsException("Credenciais inválidas");
        }
        long id = Long.parseLong(principal.getSubject());
        var account = users.findByIdForAuthentication(id).orElseThrow(
            () -> new BadCredentialsException("Credenciais inválidas"));
        Long credentialVersion = JwtConfig.credentialVersion(principal);
        if (credentialVersion == null || credentialVersion != account.getCredentialVersion()
                || input.currentPassword() == null
                || input.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(input.currentPassword(), account.getPassword())) {
            throw new BadCredentialsException("Credenciais inválidas");
        }
        String newPassword = input.newPassword();
        passwordPolicy.validate(newPassword, "newPassword");
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
        users.saveAndFlush(account);
        sessions.revokeByUser(account.getId(), clock.instant());
    }
}
