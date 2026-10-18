package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.raphael.beachcleanup.dto.*;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:refreshsessions", "spring.jpa.show-sql=false"})
@Import(RefreshSessionIntegrationTests.TimeConfig.class)
class RefreshSessionIntegrationTests {
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value = new AtomicReference<>(Instant.now());
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value.get(); }
        void advance(long seconds) { value.updateAndGet(now -> now.plusSeconds(seconds)); }
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock sessionTestClock() { return new MutableClock(); }
    }
    @Autowired WebApplicationContext context;
    @Autowired UserService users;
    @Autowired UserRepository userRepository;
    @Autowired AuthSessionRepository sessions;
    @Autowired TrashCollectionRepository collections;
    @Autowired CleanupEventRepository events;
    @Autowired MutableClock clock;
    @Autowired JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;
    long ownerId;

    @BeforeEach void setup() {
        collections.deleteAll(); userRepository.deleteAll(); events.deleteAll();
        clock.value.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        ownerId = users.insert(new User(null, "Ana", "ana@example.com", "senha-inicial", 0.0)).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void loginStoresOnlyHashesAndRefreshRotatesWithoutExtendingAbsoluteDeadline() throws Exception {
        var first = login();
        var claims = decoder.decode(first.accessToken());
        assertEquals(1800, first.expiresIn()); assertEquals(604800, first.refreshExpiresIn());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(first.refreshToken().getBytes(StandardCharsets.US_ASCII)));
        assertEquals(hash, jdbc.queryForObject("select token_hash from tb_refresh_token", String.class));
        assertFalse(first.toString().contains(first.refreshToken()));
        assertFalse(new RefreshTokenDTO(first.refreshToken()).toString().contains(first.refreshToken()));
        assertFalse(mapper.writeValueAsString(new RefreshTokenDTO(first.refreshToken())).contains(first.refreshToken()));
        clock.advance(1800);
        access(first, 401);
        var next = refresh(first, 200);
        assertNotEquals(first.refreshToken(), next.refreshToken()); assertNotEquals(first.accessToken(), next.accessToken());
        assertEquals(603000, next.refreshExpiresIn());
        assertEquals(claims.getClaimAsString("sid"), decoder.decode(next.accessToken()).getClaimAsString("sid"));
        assertEquals(2, jdbc.queryForObject("select count(*) from tb_refresh_token", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from tb_refresh_token where consumed = true", Integer.class));
        access(next, 200);
    }

    @Test void replayCommitsFamilyRevocationIncludingAlreadyIssuedAccessButPreservesOtherSessions() throws Exception {
        var first = login(); var other = login(); var next = refresh(first, 200);
        refresh(first, 401);
        access(first, 401); access(next, 401); refresh(next, 401);
        access(other, 200); refresh(other, 200);
        assertEquals(1, jdbc.queryForObject("select count(*) from tb_auth_session where revoked_at is not null", Integer.class));
    }

    @Test void logoutCanUseConsumedTokenIsIdempotentAndRevokesOnlyThatFamily() throws Exception {
        var first = login(); var other = login(); var next = refresh(first, 200);
        for (int i = 0; i < 2; i++) mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body(first)))
            .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        access(first, 401); access(next, 401); refresh(next, 401); access(other, 200);
        mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + "x".repeat(43) + "\"}" )).andExpect(status().isNoContent());
    }

    @Test void refreshExpiryHasExactBoundaryAndAccessNeverOutlivesTheSession() throws Exception {
        var first = login();
        clock.advance(AuthService.REFRESH_LIFETIME_SECONDS - 10);
        var last = refresh(first, 200);
        assertEquals(10, last.refreshExpiresIn()); assertEquals(10, last.expiresIn()); access(last, 200);
        clock.advance(10);
        refresh(last, 401); access(last, 401);
    }

    @Test void passwordChangeRevokesAllRefreshFamiliesAndOtherUsersRemainValid() throws Exception {
        var first = login(); var other = login(); var rotated = refresh(first, 200);
        users.insert(new User(null, "Bia", "bia@example.com", "senha-inicial", 0.0));
        var bia = context.getBean(AuthService.class).login(new LoginDTO("bia@example.com", "senha-inicial"));
        mvc.perform(put("/auth/password").header("Authorization", "Bearer " + rotated.accessToken())
            .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"senha-inicial\",\"newPassword\":\"senha-nova\"}"))
            .andExpect(status().isNoContent());
        for (var token : List.of(first, other, rotated)) { access(token, 401); refresh(token, 401); }
        assertDoesNotThrow(() -> decoder.decode(bia.accessToken()));
        assertDoesNotThrow(() -> context.getBean(AuthService.class).refresh(bia.refreshToken()));
    }

    @Test void simultaneousRefreshesAllowOneRotationThenRevokeTheFamilyOnReplay() throws Exception {
        var first = login();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<org.springframework.mock.web.MockHttpServletResponse> call = () -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body(first)))
                    .andReturn().getResponse();
            };
            var a = executor.submit(call); var b = executor.submit(call); start.countDown();
            var responses = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(List.of(200, 401), responses.stream().map(response -> response.getStatus()).sorted().toList());
            var winner = mapper.readValue(responses.stream().filter(response -> response.getStatus() == 200).findFirst().orElseThrow()
                .getContentAsString(), TokenDTO.class);
            access(winner, 401); refresh(winner, 401);
            assertEquals(2, jdbc.queryForObject("select count(*) from tb_refresh_token", Integer.class));
        }
    }

    @Test void logoutAndRefreshRacingCannotLeaveAnActiveDescendant() throws Exception {
        var first = login();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var renewal = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body(first))).andReturn().getResponse();
            });
            var logout = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body(first))).andReturn().getResponse();
            });
            start.countDown(); var response = renewal.get(20, TimeUnit.SECONDS);
            assertEquals(204, logout.get(20, TimeUnit.SECONDS).getStatus());
            assertTrue(response.getStatus() == 200 || response.getStatus() == 401);
            if (response.getStatus() == 200) { var rotated = mapper.readValue(response.getContentAsString(), TokenDTO.class); access(rotated, 401); refresh(rotated, 401); }
            access(first, 401); refresh(first, 401);
        }
    }

    @Test void explicitJsonCredentialsAreRequiredAndNeverEchoedInErrors() throws Exception {
        var first = login();
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .content("refreshToken=" + first.refreshToken())).andExpect(status().isUnsupportedMediaType());
        for (var route : List.of("/auth/refresh", "/auth/logout")) {
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Set-Cookie"));
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"not-a-token\"}"))
                .andExpect(status().isBadRequest());
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).header("Cookie", "refreshToken=" + first.refreshToken()).content("{}"))
                .andExpect(status().isBadRequest());
        }
        access(first, 200);
        var result = mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + "x".repeat(43) + "\"}" )).andExpect(status().isUnauthorized()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("x".repeat(43)));
    }

    private TokenDTO login() throws Exception {
        var response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"ana@example.com\",\"password\":\"senha-inicial\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse();
        return mapper.readValue(response.getContentAsString(), TokenDTO.class);
    }
    private String body(TokenDTO token) { return mapper.writeValueAsString(Map.of("refreshToken", token.refreshToken())); }
    private TokenDTO refresh(TokenDTO token, int expected) throws Exception {
        var response = mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body(token)))
            .andExpect(status().is(expected)).andReturn().getResponse();
        return expected == 200 ? mapper.readValue(response.getContentAsString(), TokenDTO.class) : null;
    }
    private void access(TokenDTO token, int expected) throws Exception {
        mvc.perform(get("/users/{id}", ownerId).header("Authorization", "Bearer " + token.accessToken())).andExpect(status().is(expected));
    }
}
