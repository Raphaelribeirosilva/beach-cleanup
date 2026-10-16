package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.raphael.beachcleanup.entities.*;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.UserService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:staleforms", "spring.jpa.show-sql=false"})
class StaleFormIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired CleanupEventRepository events;
    @Autowired TrashCollectionRepository collections;
    @Autowired UserService userService;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;
    long userId, eventId, collectionId;

    @BeforeEach
    void setUp() {
        collections.deleteAll();
        users.deleteAll();
        events.deleteAll();
        var owner = userService.insert(new User(null, "Ana", "ana@example.com", "senha-segura", 0.0));
        userId = owner.getId();
        eventId = events.saveAndFlush(new CleanupEvent(null, "Mutirao", "Praia A",
            LocalDateTime.parse("2026-10-15T08:00:00"), "Agendado")).getId();
        var collection = new TrashCollection(null, "Praia A", "Vidro", 2.0, LocalDate.parse("2026-10-15"));
        collection.setVolunteer(owner);
        collectionId = collections.saveAndFlush(collection).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
            .defaultRequest(get("/").with(user("admin").roles("ADMIN"))).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"users", "cleanupEvent", "trashCollection"})
    void oldFormCannotOverwriteAnEditCommittedBeforeItsRequest(String resource) throws Exception {
        String path = path(resource);
        // Independent HTTP reads simulate two forms opened before either is saved.
        ObjectNode first = read(path);
        ObjectNode oldForm = read(path);
        String field = editableField(resource);
        long originalVersion = first.get("version").asLong();
        assertTrue(originalVersion >= 0);
        first.put(field, "Primeira edicao");
        ObjectNode saved = save(path, first);
        assertTrue(saved.get("version").asLong() > originalVersion);

        oldForm.put(field, "Formulario antigo");
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(oldForm)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Concurrent update"))
            .andExpect(jsonPath("$.path").value(path));
        assertEquals(saved, read(path), "Rejected form must preserve all current fields and the version");

        ObjectNode reloaded = read(path);
        reloaded.put(field, "Edicao revisada");
        ObjectNode updated = save(path, reloaded);
        assertEquals("Edicao revisada", updated.get(field).asText());
        assertTrue(updated.get("version").asLong() > saved.get("version").asLong());
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(reloaded)))
            .andExpect(status().isConflict());
        assertEquals(updated, read(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"users", "cleanupEvent", "trashCollection"})
    void missingNullNegativeAndMalformedVersionsCannotBypassProtection(String resource) throws Exception {
        String path = path(resource);
        ObjectNode original = read(path);
        for (String version : new String[] {null, "null", "-1", "\"invalid\"", "9223372036854775808"}) {
            ObjectNode input = original.deepCopy();
            input.put(editableField(resource), "Nao deve salvar");
            input.remove("version");
            if (version != null) input.set("version", mapper.readTree(version));
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)))
                .andExpect(status().isBadRequest());
            assertEquals(original, read(path));
        }
        ObjectNode future = original.deepCopy();
        future.put("version", original.get("version").asLong() + 100);
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(future)))
            .andExpect(status().isConflict());
        assertEquals(original, read(path));
    }

    @Test
    void unauthorizedRequestsCannotInspectVersionConflictsAndTotalsRemainCorrect() throws Exception {
        ObjectNode collection = read(path("trashCollection"));
        collection.put("version", 999);
        mvc.perform(put(path("trashCollection")).with(user("999999").roles("VOLUNTEER"))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(collection)))
            .andExpect(status().isForbidden());
        mvc.perform(put(path("cleanupEvent")).with(user(Long.toString(userId)).roles("VOLUNTEER"))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(read(path("cleanupEvent")))))
            .andExpect(status().isForbidden());
        assertEquals(2.0, read(path("users")).get("totalCollectedKg").asDouble());
        assertEquals(2.0, read(path("trashCollection")).get("weightKg").asDouble());
    }

    private String path(String resource) {
        return "/" + resource + "/" + switch (resource) {
            case "users" -> userId;
            case "cleanupEvent" -> eventId;
            default -> collectionId;
        };
    }

    private String editableField(String resource) {
        return switch (resource) {
            case "users" -> "name";
            case "cleanupEvent" -> "title";
            default -> "beach";
        };
    }

    private ObjectNode read(String path) throws Exception {
        var response = mvc.perform(get(path)).andExpect(status().isOk())
            .andExpect(jsonPath("$.version").isNumber()).andReturn().getResponse();
        return (ObjectNode) mapper.readTree(response.getContentAsString());
    }

    private ObjectNode save(String path, ObjectNode input) throws Exception {
        var response = mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(input))).andExpect(status().isOk())
            .andExpect(jsonPath("$.version").isNumber()).andReturn().getResponse();
        return (ObjectNode) mapper.readTree(response.getContentAsString());
    }
}
