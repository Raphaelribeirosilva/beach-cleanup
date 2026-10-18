package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.raphael.beachcleanup.config.JwtConfig;
import com.raphael.beachcleanup.dto.ChangePasswordDTO;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.entities.UserRole;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.UserService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:passwordtests", "spring.jpa.show-sql=false"
})
class PasswordChangeIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired CleanupEventRepository events;
    @Autowired TrashCollectionRepository collections;
    @Autowired UserService userService;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        collections.deleteAll();
        users.deleteAll();
        events.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void changingPasswordRevokesAllPreviousTokensAndPreservesOtherAccountsAndProfile() throws Exception {
        User account = account("ana@example.com", "senha-inicial-forte");
        User other = account("bia@example.com", "outra-senha-forte");
        String oldHash = account.getPassword();
        String otherHash = other.getPassword();
        String first = login("ana@example.com", "senha-inicial-forte");
        String second = login("ana@example.com", "senha-inicial-forte");
        String otherToken = login("bia@example.com", "outra-senha-forte");
        mvc.perform(put("/auth/password").header("Authorization", "Bearer " + first)
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"currentPassword":"senha-inicial-forte","newPassword":"senha-nova-forte",
                 "userId":%d,"role":"ADMIN","credentialVersion":999}
                """.formatted(other.getId())))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
        var saved = users.findById(account.getId()).orElseThrow();
        assertNotEquals(oldHash, saved.getPassword());
        assertTrue(passwords.matches("senha-nova-forte", saved.getPassword()));
        assertFalse(passwords.matches("senha-inicial-forte", saved.getPassword()));
        assertEquals(1, saved.getCredentialVersion());
        assertEquals(account.getName(), saved.getName());
        assertEquals(account.getEmail(), saved.getEmail());
        assertEquals(UserRole.VOLUNTEER, saved.getRole());
        assertEquals(0.0, saved.getTotalCollectedKg());
        assertEquals(otherHash, users.findById(other.getId()).orElseThrow().getPassword());
        for (String token : new String[] {first, second}) {
            mvc.perform(get("/users/" + account.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
            change(token, "senha-nova-forte", "senha-terceira-forte", 401);
        }
        mvc.perform(get("/users/" + other.getId()).header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isOk());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"ana@example.com\",\"password\":\"senha-inicial-forte\"}"))
            .andExpect(status().isUnauthorized());
        String fresh = login("ana@example.com", "senha-nova-forte");
        assertEquals(1L, JwtConfig.credentialVersion(decoder.decode(fresh)));
        mvc.perform(get("/users/" + account.getId()).header("Authorization", "Bearer " + fresh))
            .andExpect(status().isOk()).andExpect(jsonPath("$..credentialVersion").isEmpty())
            .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void invalidChangesDoNotUpdateHashOrRevokeValidTokens() throws Exception {
        User account = account("ana@example.com", "senha-inicial-forte");
        String original = account.getPassword();
        String token = login("ana@example.com", "senha-inicial-forte");
        for (String body : new String[] {"{}", "{\"currentPassword\":\" \"}",
                "{\"currentPassword\":\"senha-inicial-forte\",\"newPassword\":\" \"}"}) {
            mvc.perform(put("/auth/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        }
        change(token, "senha-incorreta", "senha-nova-forte", 401);
        change(token, "a".repeat(73), "senha-nova-forte", 401);
        for (String invalid : new String[] {"a".repeat(73), "a".repeat(71) + "é", "senha-inicial-forte"}) {
            var result = mvc.perform(put("/auth/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body("senha-inicial-forte", invalid)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].fieldName").value("newPassword"))
                .andExpect(jsonPath("$.path").value("/auth/password")).andReturn();
            assertFalse(result.getResponse().getContentAsString().contains(invalid));
            assertFalse(result.getResponse().getContentAsString().contains("senha-inicial-forte"));
        }
        var saved = users.findById(account.getId()).orElseThrow();
        assertEquals(original, saved.getPassword());
        assertEquals(0, saved.getCredentialVersion());
        mvc.perform(get("/users/" + account.getId()).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    void utf8BoundaryIsAcceptedAndSuccessiveChangesRevokeEachTokenGeneration() throws Exception {
        String initial = "a".repeat(70) + "é";
        String next = "b".repeat(70) + "é";
        User account = account("ana@example.com", initial);
        String token = login("ana@example.com", initial);
        change(token, initial, next, 204);
        String second = login("ana@example.com", next);
        change(second, next, "senha-terceira-forte", 204);
        String third = login("ana@example.com", "senha-terceira-forte");
        for (String old : new String[] {token, second}) {
            mvc.perform(get("/users/" + account.getId()).header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
        }
        assertEquals(2, users.findById(account.getId()).orElseThrow().getCredentialVersion());
        mvc.perform(get("/users/" + account.getId()).header("Authorization", "Bearer " + third))
            .andExpect(status().isOk());
    }

    @Test
    void passwordChangeRequiresAuthenticationAndCredentialVersionClaim() throws Exception {
        User account = account("ana@example.com", "senha-inicial-forte");
        mvc.perform(put("/auth/password").contentType(MediaType.APPLICATION_JSON)
            .content(body("senha-inicial-forte", "senha-nova-forte")))
            .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(JwtConfig.ISSUER).audience(List.of(JwtConfig.AUDIENCE))
            .subject(account.getId().toString()).issuedAt(now).expiresAt(now.plusSeconds(900));
        String missing = encode(claims.build());
        change(missing, "senha-inicial-forte", "senha-nova-forte", 401);
        String mismatch = encode(claims.claim(JwtConfig.CREDENTIAL_VERSION_CLAIM, 99).build());
        change(mismatch, "senha-inicial-forte", "senha-nova-forte", 401);
        String invalidType = encode(claims.claim(JwtConfig.CREDENTIAL_VERSION_CLAIM, "invalido").build());
        change(invalidType, "senha-inicial-forte", "senha-nova-forte", 401);
        assertEquals(account.getPassword(), users.findById(account.getId()).orElseThrow().getPassword());
    }

    @Test
    void administratorsChangeOnlyTheirOwnPasswordAndPasswordDtosNeverSerializeSecrets() throws Exception {
        User admin = account("admin@example.com", "senha-inicial-forte");
        admin.setRole(UserRole.ADMIN);
        users.save(admin);
        User other = account("bia@example.com", "outra-senha-forte");
        String token = login("admin@example.com", "senha-inicial-forte");
        change(token, "outra-senha-forte", "senha-nova-forte", 401);
        change(token, "senha-inicial-forte", "senha-nova-forte", 204);
        assertEquals(UserRole.ADMIN, users.findById(admin.getId()).orElseThrow().getRole());
        assertEquals(other.getPassword(), users.findById(other.getId()).orElseThrow().getPassword());
        var input = mapper.readValue(body("senha-inicial-forte", "senha-nova-forte"), ChangePasswordDTO.class);
        assertEquals("senha-inicial-forte", input.currentPassword());
        assertEquals("senha-nova-forte", input.newPassword());
        String json = mapper.writeValueAsString(input);
        assertFalse(json.contains("senha-inicial-forte"));
        assertFalse(json.contains("senha-nova-forte"));
        String entityJson = mapper.writeValueAsString(admin);
        assertFalse(entityJson.contains("credentialVersion"));
        assertFalse(entityJson.contains("rowVersion"));
    }

    @Test
    void concurrentChangesCannotOverwriteEachOther() throws Exception {
        User account = account("ana@example.com", "senha-inicial-forte");
        String token = login("ana@example.com", "senha-inicial-forte");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return mvc.perform(put("/auth/password").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body("senha-inicial-forte", "senha-primeira-forte")))
                    .andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return mvc.perform(put("/auth/password").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body("senha-inicial-forte", "senha-segunda-forte")))
                    .andReturn().getResponse().getStatus();
            });
            start.countDown();
            List<Integer> statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 204).count());
            assertTrue(statuses.stream().allMatch(status -> status == 204 || status == 401 || status == 409));
        }
        var saved = users.findById(account.getId()).orElseThrow();
        assertEquals(1, saved.getCredentialVersion());
        assertNotEquals(passwords.matches("senha-primeira-forte", saved.getPassword()),
            passwords.matches("senha-segunda-forte", saved.getPassword()));
    }

    private User account(String email, String password) {
        return userService.insert(new User(null, "Voluntario", email, password, 0.0));
    }

    private String login(String email, String password) throws Exception {
        var response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(java.util.Map.of("email", email, "password", password))))
            .andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).get("accessToken").asText();
    }

    private void change(String token, String current, String next, int expected) throws Exception {
        mvc.perform(put("/auth/password").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(body(current, next)))
            .andExpect(status().is(expected));
    }

    private String body(String current, String next) {
        return mapper.writeValueAsString(java.util.Map.of("currentPassword", current, "newPassword", next));
    }

    private String encode(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    }
}
