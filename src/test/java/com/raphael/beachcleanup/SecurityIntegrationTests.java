package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.raphael.beachcleanup.config.JwtConfig;
import com.raphael.beachcleanup.entities.*;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.UserService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:securitytests",
    "spring.jpa.show-sql=false"
})
class SecurityIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired CleanupEventRepository events;
    @Autowired TrashCollectionRepository collections;
    @Autowired UserService userService;
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
    void registrationAndLoginIssueUsableTokenWithoutAllowingRoleEscalation() throws Exception {
        var registration = mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"Ana","email":"ANA@EXAMPLE.COM","password":"senha-segura","role":"ADMIN"}
            """)).andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("ana@example.com"))
            .andExpect(jsonPath("$..password").isEmpty()).andReturn();
        long id = mapper.readTree(registration.getResponse().getContentAsString()).get("id").asLong();
        assertEquals(UserRole.VOLUNTEER, users.findById(id).orElseThrow().getRole());
        var login = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
            {"email":"ANA@EXAMPLE.COM","password":"senha-segura"}
            """)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.expiresIn").value(900))
            .andExpect(jsonPath("$.userId").value(id)).andExpect(jsonPath("$.volunteerId").doesNotExist())
            .andExpect(jsonPath("$.role").value("VOLUNTEER"))
            .andExpect(jsonPath("$..password").isEmpty()).andReturn();
        assertNull(login.getResponse().getCookie("JSESSIONID"));
        String token = mapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        Jwt jwt = decoder.decode(token);
        assertEquals(Long.toString(id), jwt.getSubject());
        assertEquals(JwtConfig.ISSUER, jwt.getIssuer().toString());
        assertEquals(List.of(JwtConfig.AUDIENCE), jwt.getAudience());
        assertEquals(900, java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds());
        assertFalse(jwt.getClaims().containsKey("password"));
        mvc.perform(get("/users/" + id).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mvc.perform(get("/users").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void anonymousAndBadCredentialsReturnConsistentErrors() throws Exception {
        account("ana@example.com", false);
        mvc.perform(get("/cleanupEvent")).andExpect(status().isOk());
        for (String path : new String[] {"/users", "/users/1", "/trashCollection"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value(path));
        }
        for (String path : new String[] {"/cleanupEvent", "/trashCollection"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        }
        for (String email : new String[] {"ana@example.com", "naoexiste@example.com"}) {
            var response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"segredo-incorreto\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Autenticação necessária ou credenciais inválidas."))
                .andExpect(jsonPath("$.accessToken").doesNotExist()).andReturn();
            assertFalse(response.getResponse().getContentAsString().contains("segredo-incorreto"));
            assertFalse(response.getResponse().getContentAsString().contains(email));
        }
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"ana@example.com\",\"password\":\"" + "a".repeat(73) + "\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void usersCannotReadOrChangeOtherProfilesOrEvents() throws Exception {
        User first = account("first@example.com", false);
        User second = account("second@example.com", false);
        String token = login("first@example.com");
        long eventId = events.save(new CleanupEvent(null, "Mutirao", "Praia A",
            LocalDateTime.parse("2026-10-15T08:00:00"), "Agendado")).getId();
        for (String method : new String[] {"GET", "PUT", "DELETE"}) {
            var request = request(org.springframework.http.HttpMethod.valueOf(method), "/users/" + second.getId());
            mvc.perform(request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alterado\",\"email\":\"alterado@example.com\"}"))
                .andExpect(status().isForbidden());
        }
        mvc.perform(post("/cleanupEvent").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(eventJson())).andExpect(status().isForbidden());
        mvc.perform(put("/cleanupEvent/" + eventId).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(eventJson())).andExpect(status().isForbidden());
        mvc.perform(delete("/cleanupEvent/" + eventId).header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mvc.perform(put("/users/" + first.getId()).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Nome novo\",\"email\":\"new@example.com\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk());
        assertEquals(UserRole.VOLUNTEER, users.findById(first.getId()).orElseThrow().getRole());
        assertEquals("second@example.com", users.findById(second.getId()).orElseThrow().getEmail());
        mvc.perform(get("/users/" + first.getId()).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mvc.perform(delete("/users/" + first.getId()).header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
        mvc.perform(get("/trashCollection").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void collectionOwnershipIsBoundFilteredAndCannotBeTransferred() throws Exception {
        User first = account("first@example.com", false);
        User second = account("second@example.com", false);
        String firstToken = login("first@example.com");
        String secondToken = login("second@example.com");
        long id = createCollection(firstToken, collectionJson(""));
        createCollection(secondToken, collectionJson(""));
        assertEquals(first.getId(), collections.findById(id).orElseThrow().getVolunteer().getId());
        mvc.perform(get("/trashCollection").header("Authorization", "Bearer " + firstToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(id));
        for (String method : new String[] {"GET", "PUT", "DELETE"}) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/trashCollection/" + id)
                .header("Authorization", "Bearer " + secondToken).contentType(MediaType.APPLICATION_JSON)
                .content(collectionJson(""))).andExpect(status().isForbidden());
        }
        String otherOwner = ",\"volunteer\":{\"id\":" + second.getId() + "}";
        mvc.perform(post("/trashCollection").header("Authorization", "Bearer " + firstToken)
            .contentType(MediaType.APPLICATION_JSON).content(collectionJson(otherOwner)))
            .andExpect(status().isForbidden());
        mvc.perform(put("/trashCollection/" + id).header("Authorization", "Bearer " + firstToken)
            .contentType(MediaType.APPLICATION_JSON).content(collectionJson(otherOwner)))
            .andExpect(status().isForbidden());
        mvc.perform(put("/trashCollection/" + id).header("Authorization", "Bearer " + firstToken)
            .contentType(MediaType.APPLICATION_JSON).content(collectionJson(",\"volunteer\":null")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.volunteer.id").value(first.getId()));
        assertEquals(2, collections.count());
        mvc.perform(delete("/trashCollection/" + id).header("Authorization", "Bearer " + firstToken))
            .andExpect(status().isNoContent());
    }

    @Test
    void administratorsManageEventsAndAllCollectionsAndDemotionTakesEffectImmediately() throws Exception {
        User admin = account("admin@example.com", true);
        String token = login("admin@example.com");
        mvc.perform(get("/users").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        var result = mvc.perform(post("/cleanupEvent").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(eventJson())).andExpect(status().isCreated()).andReturn();
        long id = mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(put("/cleanupEvent/" + id).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(eventJson())).andExpect(status().isOk());
        createCollection(token, collectionJson(""));
        mvc.perform(get("/trashCollection").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/cleanupEvent/" + id).header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
        admin.setRole(UserRole.VOLUNTEER);
        users.save(admin);
        mvc.perform(get("/users").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void invalidExpiredAndWronglySignedTokensAreRejectedAndClaimsCannotGrantRoles() throws Exception {
        User account = account("ana@example.com", false);
        String id = account.getId().toString();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(JwtConfig.ISSUER).audience(List.of(JwtConfig.AUDIENCE))
            .claim(JwtConfig.CREDENTIAL_VERSION_CLAIM, 0)
            .subject(id).issuedAt(now).expiresAt(now.plusSeconds(900)).build();
        JwtEncoder foreign = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(new byte[32], "HmacSHA256"))
            .algorithm(MacAlgorithm.HS256).build();
        String[] invalid = {
            "token-invalido", encode(foreign, claims),
            encode(encoder, JwtClaimsSet.from(claims).issuedAt(now.minusSeconds(1800)).expiresAt(now.minusSeconds(1)).build()),
            encode(encoder, JwtClaimsSet.from(claims).issuer("outro-emissor").build()),
            encode(encoder, JwtClaimsSet.from(claims).audience(List.of("outra-api")).build()),
            encode(encoder, JwtClaimsSet.from(claims).notBefore(now.plusSeconds(300)).build()),
            encode(encoder, JwtClaimsSet.from(claims).subject("nao-numerico").build()),
            encode(encoder, JwtClaimsSet.from(claims).subject("999999999").build()),
            encode(encoder, JwtClaimsSet.builder().issuer(JwtConfig.ISSUER)
                .audience(List.of(JwtConfig.AUDIENCE)).subject(id).issuedAt(now).build()),
            encode(encoder, JwtClaimsSet.builder().issuer(JwtConfig.ISSUER)
                .subject(id).issuedAt(now).expiresAt(now.plusSeconds(900)).build())
        };
        for (String token : invalid) {
            mvc.perform(get("/users/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
        String forgedRole = encode(encoder, JwtClaimsSet.from(claims).claim("role", "ADMIN").build());
        mvc.perform(get("/users").header("Authorization", "Bearer " + forgedRole))
            .andExpect(status().isForbidden());
    }

    @Test
    void duplicateEmailIsRejectedAndFailedUpdatePreservesTheAccount() throws Exception {
        User first = account("ana@example.com", false);
        User second = account("bia@example.com", false);
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Outra Ana\",\"email\":\"ANA@EXAMPLE.COM\",\"password\":\"senha-segura\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Email conflict"));
        String token = login("bia@example.com");
        mvc.perform(put("/users/" + second.getId()).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Alterado\",\"email\":\"ANA@EXAMPLE.COM\"}"))
            .andExpect(status().isConflict());
        assertEquals(2, users.count());
        assertEquals("bia@example.com", users.findById(second.getId()).orElseThrow().getEmail());
        assertEquals("ana@example.com", users.findById(first.getId()).orElseThrow().getEmail());
    }

    private User account(String email, boolean admin) {
        User account = userService.insert(new User(null, "Voluntario", email, "senha-segura", 0.0));
        if (admin) {
            account.setRole(UserRole.ADMIN);
            account = users.save(account);
        }
        return account;
    }

    private String login(String email) throws Exception {
        var result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"senha-segura\"}"))
            .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long createCollection(String token, String body) throws Exception {
        var result = mvc.perform(post("/trashCollection").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String encode(JwtEncoder jwtEncoder, JwtClaimsSet claims) {
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    }

    private String collectionJson(String reference) {
        return "{\"beach\":\"Praia A\",\"materialType\":\"Vidro\",\"weightKg\":1,\"registrationDate\":\"2026-10-15\"" + reference + "}";
    }

    private String eventJson() {
        return "{\"title\":\"Mutirao\",\"location\":\"Praia A\",\"dateTime\":\"2026-10-15T08:00:00\",\"status\":\"Agendado\"}";
    }
}
