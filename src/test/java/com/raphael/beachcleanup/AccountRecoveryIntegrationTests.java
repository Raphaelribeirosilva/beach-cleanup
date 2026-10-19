package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.raphael.beachcleanup.dto.*;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.security.AuthAbuseFilter;
import com.raphael.beachcleanup.services.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:accountrecovery", "spring.jpa.show-sql=false",
    "security.accounts.require-verified-email=true", "security.abuse.enabled=true"})
@Import(RefreshSessionIntegrationTests.TimeConfig.class)
class AccountRecoveryIntegrationTests {
    static final String PASSWORD = "Uma praia mais limpa!", NEW_PASSWORD = "Outro oceano mais limpo!";
    @Autowired WebApplicationContext context;
    @Autowired UserService users;
    @Autowired UserRepository repository;
    @Autowired TrashCollectionRepository collections;
    @Autowired CleanupEventRepository events;
    @Autowired AuthRateLimitRepository limits;
    @Autowired AccountActionTokenRepository tokens;
    @Autowired AccountMailGateway mail;
    @Autowired RefreshSessionIntegrationTests.MutableClock clock;
    @Autowired ThreadPoolTaskExecutor accountMailExecutor;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired JwtDecoder decoder;
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        idle(); collections.deleteAll(); repository.deleteAll(); events.deleteAll(); limits.deleteAll(); mail.clearSimulated();
        clock.value.set(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(AuthAbuseFilter.class)).apply(springSecurity()).build();
    }
    @AfterEach void finish() throws Exception { idle(); }

    @Test void signupRequiresConfirmationAndNeitherApiNorDatabaseExposeTheSecret() throws Exception {
        var result = mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
            "name", "Ana", "email", "ana@example.com", "password", PASSWORD, "emailVerified", true))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.emailVerified").value(false)).andReturn();
        login("ana@example.com", PASSWORD, 401);
        idle(); String token = token("Confirmar e-mail");
        assertFalse(result.getResponse().getContentAsString().contains(token));
        assertNotEquals(token, jdbc.queryForObject("select token_hash from tb_account_action_token", String.class));
        confirm(token, 204); confirm(token, 400);
        login("ana@example.com", PASSWORD, 200);
        assertTrue(repository.findByEmail("ana@example.com").orElseThrow().isEmailVerified());
    }

    @Test void recoveryIsGenericAndResetIsSingleUseAndRevokesEverySession() throws Exception {
        var owner = account(true);
        var first = auth.login(new LoginDTO(owner.getEmail(), PASSWORD));
        var second = auth.login(new LoginDTO(owner.getEmail(), PASSWORD));
        String known = request("/auth/recovery", owner.getEmail(), 202).getResponse().getContentAsString();
        String unknown = request("/auth/recovery", "missing@example.com", 202).getResponse().getContentAsString();
        assertEquals(known, unknown); idle();
        assertEquals(1, mail.simulatedMessages().size()); String token = token("Redefinir senha");
        reset(token, NEW_PASSWORD, 204); reset(token, NEW_PASSWORD + " outra", 400);
        for (var session : List.of(first, second)) {
            assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(session.accessToken()));
            assertThrows(org.springframework.security.authentication.BadCredentialsException.class, () -> auth.refresh(session.refreshToken()));
        }
        login(owner.getEmail(), PASSWORD, 401); login(owner.getEmail(), NEW_PASSWORD, 200);
        idle(); assertTrue(mail.simulatedMessages().stream().anyMatch(message -> message.subject().equals("Senha alterada")));
        assertTrue(mail.simulatedMessages().stream().noneMatch(message -> message.text().contains(NEW_PASSWORD)));
    }

    @Test void expiryAndPurposeAreCheckedWithoutConsumingUsableTokens() throws Exception {
        var owner = account(false);
        request("/auth/verification", owner.getEmail(), 202); idle(); String verify = token("Confirmar e-mail");
        reset(verify, NEW_PASSWORD, 400); confirm(verify, 204);
        request("/auth/recovery", owner.getEmail(), 202); idle(); String reset = token("Redefinir senha");
        confirm(reset, 400); clock.advance(900); reset(reset, NEW_PASSWORD, 400);
        request("/auth/verification", "unknown@example.com", 202); idle();
        assertEquals(2, mail.simulatedMessages().size());
    }

    @Test void verificationExpiresAt24HoursAndChangingEmailInvalidatesOldLinksAndSessions() throws Exception {
        var owner = account(false);
        request("/auth/verification", owner.getEmail(), 202); idle(); String verify = token("Confirmar e-mail");
        clock.advance(86400); confirm(verify, 400);
        request("/auth/verification", owner.getEmail(), 202); idle(); confirm(token("Confirmar e-mail"), 204);
        var session = auth.login(new LoginDTO(owner.getEmail(), PASSWORD));
        request("/auth/recovery", owner.getEmail(), 202); idle(); String oldReset = token("Redefinir senha");
        var current = repository.findById(owner.getId()).orElseThrow();
        mvc.perform(put("/users/{id}", owner.getId()).header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("name", "Ana", "email", "new@example.com", "version", current.getRowVersion(), "currentPassword", "wrong"))))
            .andExpect(status().isBadRequest());
        assertEquals(owner.getEmail(), repository.findById(owner.getId()).orElseThrow().getEmail());
        assertDoesNotThrow(() -> decoder.decode(session.accessToken()));
        mvc.perform(put("/users/{id}", owner.getId()).header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("name", "Ana", "email", "new@example.com", "version", current.getRowVersion(), "currentPassword", PASSWORD))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(false));
        reset(oldReset, NEW_PASSWORD, 400);
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(session.accessToken()));
        login("new@example.com", PASSWORD, 401);
        idle(); confirm(token("Confirmar e-mail"), 204); login("new@example.com", PASSWORD, 200);
    }

    @Test void requestsForUnverifiedAccountsDoNotResetOrLockThemAndMailLimitsAreSilent() throws Exception {
        var owner = account(false);
        request("/auth/recovery", owner.getEmail(), 202); idle(); assertTrue(mail.simulatedMessages().isEmpty());
        for (int i = 0; i < 4; i++) request("/auth/verification", owner.getEmail(), 202);
        idle(); assertEquals(3, mail.simulatedMessages().size());
        confirm(token("Confirmar e-mail"), 204); login(owner.getEmail(), PASSWORD, 200);
    }

    @Test void loginAndIpLimitsReturn429PersistCountersAndIgnoreSpoofedForwardingHeaders() throws Exception {
        account(true);
        for (int i = 0; i < 10; i++) login("ana@example.com", "wrong password", 401);
        login("ana@example.com", PASSWORD, 429);
        clock.advance(900); login("ana@example.com", PASSWORD, 200);
        for (int i = 0; i < 20; i++) mvc.perform(post("/auth/recovery").header("X-Forwarded-For", "203.0.113." + i)
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"missing@example.com\"}" )).andExpect(status().isAccepted());
        mvc.perform(post("/auth/verification").header("X-Forwarded-For", "198.51.100.1")
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"missing@example.com\"}" ))
            .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertTrue(limits.count() > 0);
        assertFalse(jdbc.queryForList("select bucket_key from tb_auth_rate_limit", String.class).toString().contains("ana@example.com"));
    }

    @Test void sevenCharacterPasswordsWorkForSignupResetAndPasswordChange() throws Exception {
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
            "name", "Bia", "email", "bia@example.com", "password", "Praia7!")))).andExpect(status().isCreated());
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
            "name", "Curta", "email", "curta@example.com", "password", "Mar7!a")))).andExpect(status().isBadRequest());
        var owner = account(true);
        request("/auth/recovery", owner.getEmail(), 202); idle();
        String resetToken = token("Redefinir senha");
        reset(resetToken, "Mar7!a", 400);
        reset(resetToken, "Mar7!az", 204);
        login(owner.getEmail(), "Mar7!az", 200);
        var session = auth.login(new LoginDTO(owner.getEmail(), "Mar7!az"));
        auth.changePassword(decoder.decode(session.accessToken()), new ChangePasswordDTO("Mar7!az", "Sol7!az"));
        login(owner.getEmail(), "Sol7!az", 200);
    }

    @Test void weakPasswordsAreRejectedAndOutstandingLinksDieAfterPasswordChange() throws Exception {
        var owner = account(true);
        for (String weak : List.of("curta", "a".repeat(20), "123456789012345")) {
            mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                "name", "Bia", "email", "bia@example.com", "password", weak)))).andExpect(status().isBadRequest());
        }
        request("/auth/recovery", owner.getEmail(), 202); idle(); String token = token("Redefinir senha");
        reset(token, "curta", 400);
        var session = auth.login(new LoginDTO(owner.getEmail(), PASSWORD));
        auth.changePassword(decoder.decode(session.accessToken()), new ChangePasswordDTO(PASSWORD, NEW_PASSWORD));
        reset(token, "Mais uma senha válida!", 400);
        login(owner.getEmail(), NEW_PASSWORD, 200);
    }

    @Test void concurrentResetConsumesOnlyOnce() throws Exception {
        var owner = account(true); request("/auth/recovery", owner.getEmail(), 202); idle(); String token = token("Redefinir senha");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> operation = () -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return mvc.perform(post("/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("token", token, "newPassword", NEW_PASSWORD))))
                .andReturn().getResponse().getStatus(); };
            var a = executor.submit(operation); var b = executor.submit(operation); start.countDown();
            assertEquals(List.of(204, 400), List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)).stream().sorted().toList());
        }
    }
    private User account(boolean verified) { var user = users.insert(new User(null, "Ana", "ana@example.com", PASSWORD, 0.0)); user.setEmailVerified(verified); return repository.saveAndFlush(user); }
    private void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (accountMailExecutor.getActiveCount() != 0 || !accountMailExecutor.getThreadPoolExecutor().getQueue().isEmpty()) {
            if (System.nanoTime() > deadline) fail("Envio simulado não terminou"); Thread.sleep(10);
        }
    }
    private String token(String subject) {
        return mail.simulatedMessages().stream().filter(message -> message.subject().equals(subject)).reduce((a, b) -> b)
            .map(message -> { var match = java.util.regex.Pattern.compile("#(?:reset|verify)=([A-Za-z0-9_-]{43})").matcher(message.text()); assertTrue(match.find()); return match.group(1); }).orElseThrow();
    }
    private MvcResult request(String path, String email, int expected) throws Exception { return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("email", email)))).andExpect(status().is(expected)).andReturn(); }
    private void login(String email, String password, int expected) throws Exception { mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("email", email, "password", password)))).andExpect(status().is(expected)); }
    private void confirm(String token, int expected) throws Exception { mvc.perform(post("/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("token", token)))).andExpect(status().is(expected)); }
    private void reset(String token, String password, int expected) throws Exception { mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("token", token, "newPassword", password)))).andExpect(status().is(expected)); }
}
