package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.raphael.beachcleanup.dto.LoginDTO;
import com.raphael.beachcleanup.entities.*;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.*;

/** Shared invariants against both Hibernate/H2 and real MySQL. */
final class AuthMaintenanceAssertions {
    static String hash() { return UUID.randomUUID().toString().replace("-", "").repeat(2); }

    static AuthSession session(ApplicationContext context, User user, Instant expiry, boolean revoked) {
        var family = new AuthSession(UUID.randomUUID().toString(), user, expiry);
        if (revoked) family.revoke(context.getBean(Clock.class).instant().minusSeconds(86400));
        family = context.getBean(AuthSessionRepository.class).saveAndFlush(family);
        var used = new RefreshToken(hash(), family); used.consume();
        context.getBean(RefreshTokenRepository.class).saveAndFlush(used);
        context.getBean(RefreshTokenRepository.class).saveAndFlush(new RefreshToken(hash(), family));
        return family;
    }
    static String action(ApplicationContext context, User user, Instant expiry, boolean consumed) {
        String hash = hash(); var token = new AccountActionToken(hash, user, AccountActionService.VERIFY, expiry);
        if (consumed) token.consume();
        context.getBean(AccountActionTokenRepository.class).saveAndFlush(token); return hash;
    }
    static String rate(ApplicationContext context, Instant start) {
        String key = hash(); context.getBean(AuthRateLimitRepository.class).saveAndFlush(new AuthRateLimit(key, start)); return key;
    }

    static void preservation(ApplicationContext context) {
        var users = context.getBean(UserService.class);
        var user = users.insert(new User(null, "Manutenção", "maintenance@example.com", "Uma praia mais limpa!", 0.0));
        var now = context.getBean(Clock.class).instant();
        var sessions = context.getBean(AuthSessionRepository.class);
        var actions = context.getBean(AccountActionTokenRepository.class);
        var rates = context.getBean(AuthRateLimitRepository.class);
        var old = session(context, user, now.minus(Duration.ofDays(8)), false);
        var revokedOld = session(context, user, now.minus(Duration.ofDays(8)), true);
        var grace = session(context, user, now.minus(Duration.ofDays(6)), false);
        var revokedLive = session(context, user, now.plus(Duration.ofDays(1)), true);
        String expired = action(context, user, now.minus(Duration.ofDays(8)), false);
        String consumedOld = action(context, user, now.minus(Duration.ofDays(8)), true);
        String consumedRecent = action(context, user, now.plusSeconds(3600), true);
        String valid = action(context, user, now.plusSeconds(3600), false);
        String stale = rate(context, now.minus(Duration.ofHours(25)));
        String windowEnded = rate(context, now.minus(Duration.ofHours(2)));
        var limiter = context.getBean(com.raphael.beachcleanup.security.AbuseLimiter.class);
        assertEquals(0, limiter.consume("maintenance-test", "identity", 1, 3600));
        var auth = context.getBean(AuthService.class);
        var first = auth.login(new LoginDTO(user.getEmail(), "Uma praia mais limpa!"));
        var rotated = auth.refresh(first.refreshToken());
        var result = context.getBean(AuthDataMaintenance.class).cleanup();
        assertEquals(new AuthDataMaintenance.Result(2, 2, 1), result);
        assertFalse(sessions.existsById(old.getId())); assertFalse(sessions.existsById(revokedOld.getId()));
        assertTrue(sessions.existsById(grace.getId())); assertTrue(sessions.existsById(revokedLive.getId()));
        assertEquals(6, context.getBean(RefreshTokenRepository.class).count());
        assertFalse(actions.existsById(expired)); assertFalse(actions.existsById(consumedOld));
        assertTrue(actions.existsById(consumedRecent)); assertTrue(actions.existsById(valid));
        assertFalse(rates.existsById(stale)); assertTrue(rates.existsById(windowEnded));
        assertTrue(limiter.consume("maintenance-test", "identity", 1, 3600) > 0);
        assertTrue(context.getBean(UserRepository.class).existsById(user.getId()));
        assertDoesNotThrow(() -> context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(rotated.accessToken()));
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class, () -> auth.refresh(first.refreshToken()));
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class, () -> auth.refresh(rotated.refreshToken()));
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class,
            () -> context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(rotated.accessToken()));
        assertEquals(new AuthDataMaintenance.Result(0, 0, 0), context.getBean(AuthDataMaintenance.class).cleanup());
    }

    static void renewalRace(ApplicationContext context) throws Exception {
        var now = context.getBean(Clock.class).instant();
        String key = rate(context, now.minus(Duration.ofHours(25)));
        var rates = context.getBean(AuthRateLimitRepository.class);
        var manager = context.getBean(PlatformTransactionManager.class);
        var updated = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var renewal = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                var bucket = rates.locked(key).orElseThrow(); assertEquals(0, bucket.consume(now, 1, 3600));
                rates.flush(); updated.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            try {
                assertTrue(updated.await(5, TimeUnit.SECONDS));
                var cleanup = pool.submit(() -> context.getBean(AuthDataMaintenance.class).cleanup());
                // Cleanup selected the previous committed timestamp, then waits on the renewing transaction.
                assertThrows(TimeoutException.class, () -> cleanup.get(200, TimeUnit.MILLISECONDS));
                release.countDown(); renewal.get(10, TimeUnit.SECONDS); cleanup.get(10, TimeUnit.SECONDS);
                assertTrue(rates.existsById(key));
                long remaining = new TransactionTemplate(manager).execute(tx -> rates.locked(key).orElseThrow().consume(now, 1, 3600));
                assertTrue(remaining > 0, "A manutenção não pode reiniciar o contador renovado");
            } finally { release.countDown(); }
        }
    }
}
