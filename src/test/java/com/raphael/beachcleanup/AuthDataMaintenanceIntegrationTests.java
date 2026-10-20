package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:authmaintenance", "spring.jpa.show-sql=false",
    "security.abuse.enabled=true", "security.maintenance.enabled=false", "security.maintenance.batch-size=2",
    "security.maintenance.max-batches=2"})
@Import(RefreshSessionIntegrationTests.TimeConfig.class)
class AuthDataMaintenanceIntegrationTests {
    @Autowired ApplicationContext context;
    @Autowired AuthDataMaintenance maintenance;
    @Autowired UserService users;
    @Autowired UserRepository repository;
    @Autowired TrashCollectionRepository collections;
    @Autowired CleanupEventRepository events;
    @Autowired AuthRateLimitRepository rates;
    @Autowired AuthSessionRepository sessions;
    @Autowired AccountActionTokenRepository actions;
    @Autowired RefreshTokenRepository refresh;
    @Autowired RefreshSessionIntegrationTests.MutableClock clock;

    @BeforeEach void setup() {
        collections.deleteAll(); repository.deleteAll(); events.deleteAll(); rates.deleteAll();
        clock.value.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }
    @Test void expiredDataIsRemovedAndReplayDetectionAndActiveLimitsSurvive() { AuthMaintenanceAssertions.preservation(context); }
    @Test void aCounterRenewedDuringCleanupKeepsItsNewWindow() throws Exception { AuthMaintenanceAssertions.renewalRace(context); }

    @Test void cutoffsAreExactAndConsumptionOrRevocationNeverShortenRetention() {
        var user = users.insert(new User(null, "Manutenção", "boundary@example.com", "Uma praia mais limpa!", 0.0));
        var cutoff = clock.instant().minus(Duration.ofDays(7));
        AuthMaintenanceAssertions.session(context, user, cutoff, false);
        var retained = AuthMaintenanceAssertions.session(context, user, cutoff.plusSeconds(1), true);
        AuthMaintenanceAssertions.action(context, user, cutoff, false);
        String retainedToken = AuthMaintenanceAssertions.action(context, user, cutoff.plusSeconds(1), true);
        AuthMaintenanceAssertions.rate(context, clock.instant().minus(Duration.ofHours(24)));
        String retainedBucket = AuthMaintenanceAssertions.rate(context, clock.instant().minus(Duration.ofHours(24)).plusSeconds(1));
        assertEquals(new AuthDataMaintenance.Result(1, 1, 1), maintenance.cleanup());
        assertTrue(sessions.existsById(retained.getId())); assertEquals(2, refresh.count());
        assertTrue(actions.existsById(retainedToken)); assertTrue(rates.existsById(retainedBucket));
        clock.advance(1);
        assertEquals(new AuthDataMaintenance.Result(1, 1, 1), maintenance.cleanup());
        assertEquals(0, refresh.count());
    }

    @Test void backlogIsBoundedAndContinuesOnTheNextCycle() {
        var user = users.insert(new User(null, "Manutenção", "backlog@example.com", "Uma praia mais limpa!", 0.0));
        for (int i = 0; i < 5; i++) {
            AuthMaintenanceAssertions.session(context, user, clock.instant().minus(Duration.ofDays(8)), false);
            AuthMaintenanceAssertions.action(context, user, clock.instant().minus(Duration.ofDays(8)), i % 2 == 0);
            AuthMaintenanceAssertions.rate(context, clock.instant().minus(Duration.ofDays(2)));
        }
        assertEquals(new AuthDataMaintenance.Result(4, 4, 4), maintenance.cleanup());
        assertEquals(1, sessions.count()); assertEquals(2, refresh.count());
        assertEquals(new AuthDataMaintenance.Result(1, 1, 1), maintenance.cleanup());
        assertEquals(new AuthDataMaintenance.Result(0, 0, 0), maintenance.cleanup());
    }

    @Test void expiredPurgedActionLinksRemainInvalidAndLiveLinksStillWork() {
        var user = users.insert(new User(null, "Manutenção", "links@example.com", "Uma praia mais limpa!", 0.0));
        var service = context.getBean(AccountActionService.class);
        String old = token(service.prepareMail(new AccountMailRequest(user.getEmail(), AccountActionService.VERIFY)).text());
        clock.advance(8 * 86400);
        assertEquals(1, maintenance.cleanup().actionTokens());
        assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class, () -> service.verify(old));
        String live = token(service.prepareMail(new AccountMailRequest(user.getEmail(), AccountActionService.VERIFY)).text());
        maintenance.cleanup(); assertDoesNotThrow(() -> service.verify(live));
    }
    @Test void newLimiterScopesCannotExceedTheWindowProtectedByRetention() {
        var limiter = context.getBean(com.raphael.beachcleanup.security.AbuseLimiter.class);
        assertThrows(IllegalArgumentException.class, () -> limiter.consume("future-scope", "identity", 1,
            com.raphael.beachcleanup.security.AbuseLimiter.MAX_WINDOW_SECONDS + 1));
    }
    private String token(String body) {
        var match = java.util.regex.Pattern.compile("#verify=([A-Za-z0-9_-]{43})").matcher(body);
        assertTrue(match.find()); return match.group(1);
    }
}
