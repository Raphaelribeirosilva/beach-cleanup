package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.services.UserService;
import com.raphael.beachcleanup.dto.UserInsertDTO;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:crudtests",
    "spring.jpa.show-sql=false"
})
class CrudIntegrationTests {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository users;
    @Autowired
    private CleanupEventRepository events;
    @Autowired
    private TrashCollectionRepository collections;

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserService userService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CatalogTestData.seed(context);
        collections.deleteAll();
        users.deleteAll();
        events.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
            .defaultRequest(get("/").with(user("admin").roles("ADMIN"))).build();
    }

    @Test
    void userCrudUpdatesNameAndEmailAndPreservesManagedFields() throws Exception {
        long id = create("/users", """
            {"name":"Ana","email":"ana@example.com","password":"senha-inicial-forte"}
            """);
        String storedHash = users.findById(id).orElseThrow().getPassword();
        assertNotEquals("senha-inicial-forte", storedHash);
        assertTrue(passwordEncoder.matches("senha-inicial-forte", storedHash));
        assertFalse(passwordEncoder.matches("senha-incorreta", storedHash));
        assertEquals(0.0, users.findById(id).orElseThrow().getTotalCollectedKg().doubleValue());
        mvc.perform(get("/users"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id))
            .andExpect(jsonPath("$..password").isEmpty());
        mvc.perform(get("/users/{id}", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Ana"))
            .andExpect(jsonPath("$.totalCollectedKg").value(0.0))
            .andExpect(jsonPath("$.totalTrashCollecting").doesNotExist())
            .andExpect(jsonPath("$..password").isEmpty());
        assertEquals(0.0, jdbc.queryForObject("select total_collected_kg from tb_user where id = ?",
            Double.class, id));
        mvc.perform(put("/users/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"version":0,"id":999999,"name":"Ana Silva","email":"ana.silva@example.com",
                 "password":"outra-senha-forte","totalCollectedKg":500}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.name").value("Ana Silva"))
            .andExpect(jsonPath("$.email").value("ana.silva@example.com"))
            .andExpect(jsonPath("$..password").isEmpty());
        User saved = users.findById(id).orElseThrow();
        assertEquals(storedHash, saved.getPassword());
        assertEquals(0.0, saved.getTotalCollectedKg().doubleValue());
        deleteAndCheckMissing("/users", id);
    }

    @Test
    void eventCrudCreatesInsteadOfOverwritingAndUpdatesAllScalarFields() throws Exception {
        long existingId = newEvent().getId();
        long id = create("/cleanupEvent", """
            {"id":%d,"title":"Mutirao","location":"Praia A",
             "dateTime":"2026-10-15T08:00:00","status":"Agendado"}
            """.formatted(existingId));
        assertNotEquals(existingId, id);
        assertEquals("Evento original", events.findById(existingId).orElseThrow().getTitle());
        mvc.perform(get("/cleanupEvent")).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$..password").isEmpty());
        mvc.perform(get("/cleanupEvent/{id}", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Mutirao"))
            .andExpect(jsonPath("$..password").isEmpty());
        mvc.perform(put("/cleanupEvent/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"version":0,"id":999999,"title":"Novo titulo","location":"Praia B",
                 "dateTime":"2026-10-16T09:00:00","status":"Em andamento"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.title").value("Novo titulo"))
            .andExpect(jsonPath("$..password").isEmpty());
        CleanupEvent saved = events.findById(id).orElseThrow();
        assertEquals("Praia B", saved.getLocation());
        assertEquals(LocalDateTime.parse("2026-10-16T09:00:00"), saved.getDateTime());
        assertEquals("Em andamento", saved.getStatus());
        deleteAndCheckMissing("/cleanupEvent", id);
    }

    @Test
    void collectionCrudResolvesReferencesAndUpdatesDateAndAssociations() throws Exception {
        long userId = newUser().getId();
        long eventId = newEvent().getId();
        long id = create("/trashCollection", collectionJson(userId, eventId));
        mvc.perform(get("/trashCollection")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(id))
            .andExpect(jsonPath("$..password").isEmpty());
        mvc.perform(get("/trashCollection/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.weightKg").value(2.0))
            .andExpect(jsonPath("$.registrationDate").value("2026-10-15"))
            .andExpect(jsonPath("$.weithKg").doesNotExist())
            .andExpect(jsonPath("$.regitrationDate").doesNotExist())
            .andExpect(jsonPath("$.volunteer.totalCollectedKg").value(2.0))
            .andExpect(jsonPath("$.volunteer.name").value("Voluntario original"))
            .andExpect(jsonPath("$.event.title").value("Evento original"))
            .andExpect(jsonPath("$..password").isEmpty());
        long anotherUser = newUser().getId();
        long anotherEvent = events.save(CatalogTestData.event(context, new CleanupEvent(null, "Evento seguinte", "Praia B",
            LocalDateTime.parse("2026-10-16T08:00:00"), "Agendado"))).getId();
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"version":0,"id":999999,"beach":"Praia B","materialType":"Vidro","weightKg":3.5,
                 "registrationDate":"2026-10-16","volunteer":{"id":%d},"event":{"id":%d}}
                """.formatted(anotherUser, anotherEvent)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.volunteer.id").value(anotherUser))
            .andExpect(jsonPath("$.event.id").value(anotherEvent))
            .andExpect(jsonPath("$..password").isEmpty());
        var saved = collections.findById(id).orElseThrow();
        assertEquals("Praia B", saved.getBeach());
        assertEquals("Vidro", saved.getMaterialType());
        assertEquals(3.5, saved.getWeightKg().doubleValue());
        assertEquals(LocalDate.parse("2026-10-16"), saved.getRegistrationDate());
        var row = jdbc.queryForMap("select weight_kg, registration_date, volunteer_id from tb_trash_collection where id = ?", id);
        assertEquals(3.5, ((Number) row.get("weight_kg")).doubleValue());
        assertEquals(LocalDate.parse("2026-10-16"), ((java.sql.Date) row.get("registration_date")).toLocalDate());
        assertEquals(anotherUser, ((Number) row.get("volunteer_id")).longValue());
        deleteAndCheckMissing("/trashCollection", id);
    }

    @Test
    void legacyAccountRoutesAreNoLongerMapped() throws Exception {
        long id = newUser().getId();
        for (String method : new String[] {"GET", "POST", "PUT", "DELETE"}) {
            for (String path : new String[] {"/volunteers", "/volunteers/" + id}) {
                mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
            }
        }
        assertTrue(users.existsById(id));
    }

    @Test
    void missingIdsReturn404ForReadUpdateAndDeleteAcrossAllResources() throws Exception {
        for (String path : new String[] {"/users", "/cleanupEvent", "/trashCollection"}) {
            mvc.perform(get(path + "/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
            String validBody = switch (path) {
                case "/users" -> "{\"name\":\"Ana\",\"email\":\"ana@example.com\"}";
                case "/cleanupEvent" -> "{\"title\":\"Mutirao\",\"location\":\"Praia A\",\"dateTime\":\"2026-10-15T08:00:00\",\"status\":\"Agendado\"}";
                default -> "{\"beach\":\"Praia A\",\"materialType\":\"Vidro\",\"weightKg\":1,\"registrationDate\":\"2026-10-15\"}";
            };
            mvc.perform(put(path + "/999999").contentType(MediaType.APPLICATION_JSON).content(validBody))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
            mvc.perform(delete(path + "/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        }
    }

    @Test
    void collectionWithMissingReferenceIsNotCreated() throws Exception {
        long eventId = newEvent().getId();
        mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(999999, eventId))).andExpect(status().isNotFound());
        long userId = newUser().getId();
        mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(userId, 999999))).andExpect(status().isNotFound());
        assertEquals(0, collections.count());
    }

    @Test
    void collectionUpdateWithMissingReferenceLeavesPersistedDataIntact() throws Exception {
        long userId = newUser().getId();
        long eventId = newEvent().getId();
        long id = create("/trashCollection", collectionJson(userId, eventId));
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(999999, eventId))).andExpect(status().isNotFound());
        var saved = collections.findById(id).orElseThrow();
        assertEquals(userId, saved.getVolunteer().getId());
        assertEquals(eventId, saved.getEvent().getId());
        assertEquals(2.0, saved.getWeightKg().doubleValue());
    }

    @Test
    void referencesWithoutIdsReturn400InsteadOfPersistingNestedEntities() throws Exception {
        for (String json : new String[] {
            "{\"volunteer\":{\"name\":\"Nao cadastrado\"}}",
            "{\"event\":{\"title\":\"Nao cadastrado\"}}"
        }) {
            mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isBadRequest());
        }
        assertEquals(0, collections.count());
        assertEquals(0, users.count());
        assertEquals(0, events.count());
    }

    @Test
    void linkedResourcesReturn409AndCanBeDeletedAfterCollectionRemoval() throws Exception {
        long userId = newUser().getId();
        long eventId = newEvent().getId();
        long collectionId = create("/trashCollection", collectionJson(userId, eventId));
        mvc.perform(delete("/users/{id}", userId))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(delete("/cleanupEvent/{id}", eventId))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertTrue(users.existsById(userId));
        assertTrue(events.existsById(eventId));
        assertTrue(collections.existsById(collectionId));
        deleteAndCheckMissing("/trashCollection", collectionId);
        deleteAndCheckMissing("/users", userId);
        deleteAndCheckMissing("/cleanupEvent", eventId);
    }

    @Test
    void collectionSupportsNullReferencesAndCanRemoveExistingReferences() throws Exception {
        long id = create("/trashCollection",
            collectionJson(newUser().getId(), newEvent().getId()));
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"version":0,"beach":"Praia A","materialType":"Plastico","weightKg":1,
                 "registrationDate":"2026-10-16","volunteer":null,"event":null}
                """)).andExpect(status().isOk());
        var saved = collections.findById(id).orElseThrow();
        assertNull(saved.getVolunteer());
        assertNull(saved.getEvent());
        create("/trashCollection", """
            {"id":%d,"beach":"Praia A","materialType":"Vidro","weightKg":1,
             "registrationDate":"2026-10-16"}
            """.formatted(id));
        assertEquals(2, collections.count());
        assertEquals("Pl\u00e1stico", collections.findById(id).orElseThrow().getMaterialType());
    }

    @Test
    void samePasswordProducesDifferentHashesAndBothMatch() throws Exception {
        long first = create("/users", """
            {"name":"Ana","email":"ana@example.com","password":"mesma-senha-forte"}
            """);
        long second = create("/users", """
            {"name":"Bia","email":"bia@example.com","password":"mesma-senha-forte"}
            """);
        String firstHash = users.findById(first).orElseThrow().getPassword();
        String secondHash = users.findById(second).orElseThrow().getPassword();
        assertNotEquals(firstHash, secondHash);
        assertTrue(passwordEncoder.matches("mesma-senha-forte", firstHash));
        assertTrue(passwordEncoder.matches("mesma-senha-forte", secondHash));
    }

    @Test
    void bcryptLimitIsMeasuredInUtf8BytesAndRejectedPasswordsAreNotSaved() throws Exception {
        String accepted = "a".repeat(70) + "é";
        long id = create("/users", """
            {"name":"Ana","email":"ana@example.com","password":"%s"}
            """.formatted(accepted));
        assertTrue(passwordEncoder.matches(accepted, users.findById(id).orElseThrow().getPassword()));
        for (String rejected : new String[] {"a".repeat(73), "a".repeat(71) + "é"}) {
            MvcResult result = mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Bia","email":"bia@example.com","password":"%s"}
                    """.formatted(rejected))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].fieldName").value("password")).andReturn();
            assertFalse(result.getResponse().getContentAsString().contains(rejected));
        }
        assertEquals(1, users.count());
    }

    @Test
    void directSerializationHidesPasswordAndDeserializationStillAcceptsIt() {
        User account = newUser();
        String entityJson = objectMapper.writeValueAsString(account);
        assertFalse(entityJson.contains("password"));
        assertFalse(entityJson.contains(account.getPassword()));
        UserInsertDTO dto = objectMapper.readValue("""
            {"name":"Ana","email":"ana@example.com","password":"senha-de-entrada"}
            """, UserInsertDTO.class);
        assertEquals("senha-de-entrada", dto.getPassword());
        String dtoJson = objectMapper.writeValueAsString(dto);
        assertFalse(dtoJson.contains("password"));
        assertFalse(dtoJson.contains("senha-de-entrada"));
    }


    @Test
    void missingAndBlankRequiredFieldsReturnFieldErrorsWithoutSaving() throws Exception {
        for (String path : new String[] {"/users", "/cleanupEvent", "/trashCollection"}) {
            for (String body : new String[] {"{}", """
                {"name":"  ","email":"invalido","password":"  ","title":" ",
                 "location":" ","status":" ","beach":" ","materialType":" ","weightKg":0}
                """}) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.path").value(path))
                    .andExpect(jsonPath("$.error").value("Validation error"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors[0].fieldName").exists())
                    .andExpect(jsonPath("$.errors[0].message").exists());
            }
        }
        assertEquals(0, users.count());
        assertEquals(0, events.count());
        assertEquals(0, collections.count());
    }

    @Test
    void invalidUpdatesPreserveAllExistingData() throws Exception {
        long userId = newUser().getId();
        long eventId = newEvent().getId();
        long collectionId = create("/trashCollection", collectionJson(userId, eventId));
        String hash = users.findById(userId).orElseThrow().getPassword();
        for (String path : new String[] {"/users/" + userId, "/cleanupEvent/" + eventId,
                "/trashCollection/" + collectionId}) {
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        }
        mvc.perform(put("/users/" + userId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Outro nome\",\"email\":\"invalido\"}"))
            .andExpect(status().isBadRequest());
        assertEquals("Voluntario original", users.findById(userId).orElseThrow().getName());
        assertEquals("original@example.com", users.findById(userId).orElseThrow().getEmail());
        assertEquals(hash, users.findById(userId).orElseThrow().getPassword());
        assertEquals("Evento original", events.findById(eventId).orElseThrow().getTitle());
        var saved = collections.findById(collectionId).orElseThrow();
        assertEquals(2.0, saved.getWeightKg().doubleValue());
        assertEquals("Praia A", saved.getBeach());
        assertEquals(LocalDate.parse("2026-10-15"), saved.getRegistrationDate());
        assertEquals(userId, saved.getVolunteer().getId());
        assertEquals(eventId, saved.getEvent().getId());
    }

    @Test
    void nonPositiveAndNonFiniteWeightsAreRejected() throws Exception {
        for (String weight : new String[] {"null", "0", "-1", "1e309", "\"NaN\"", "\"Infinity\""}) {
            String json = "{\"beach\":\"Praia A\",\"materialType\":\"Vidro\",\"registrationDate\":\"2026-10-15\",\"weightKg\":" + weight + "}";
            mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        assertEquals(0, collections.count());
    }

    @Test
    void nestedReferenceValidationUsesFieldPathsAndDoesNotSave() throws Exception {
        for (String reference : new String[] {"volunteer", "event"}) {
            for (String idBody : new String[] {"{}", "{\"id\":0}", "{\"id\":-1}"}) {
                String json = "{\"beach\":\"Praia A\",\"materialType\":\"Vidro\",\"weightKg\":1,\"registrationDate\":\"2026-10-15\",\"" + reference + "\":" + idBody + "}";
                mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].fieldName").value(reference + ".id"));
            }
        }
        assertEquals(0, collections.count());
    }

    @Test
    void oversizedTextIsRejectedBeforePersistence() throws Exception {
        String oversized = "a".repeat(256);
        String[] paths = {"/users", "/cleanupEvent", "/trashCollection"};
        String[] bodies = {
            "{\"name\":\"%s\",\"email\":\"ana@example.com\",\"password\":\"senha\"}".formatted(oversized),
            "{\"title\":\"%s\",\"location\":\"Praia A\",\"dateTime\":\"2026-10-15T08:00:00\",\"status\":\"Agendado\"}".formatted(oversized),
            "{\"beach\":\"%s\",\"materialType\":\"Vidro\",\"weightKg\":1,\"registrationDate\":\"2026-10-15\"}".formatted(oversized)
        };
        for (int i = 0; i < paths.length; i++) {
            mvc.perform(post(paths[i]).contentType(MediaType.APPLICATION_JSON).content(bodies[i]))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        }
        assertEquals(0, users.count());
        assertEquals(0, events.count());
        assertEquals(0, collections.count());
    }

    @Test
    void malformedRequestsUseStandardErrorsAndDoNotExposeInput() throws Exception {
        for (String body : new String[] {"", "{", "null", "{\"dateTime\":\"data-invalida\"}"}) {
            mvc.perform(post("/cleanupEvent").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists()).andExpect(jsonPath("$.path").value("/cleanupEvent"));
        }
        mvc.perform(get("/users/id-invalido")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
        mvc.perform(patch("/users/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"))
            .andExpect(jsonPath("$.status").value(405));
        mvc.perform(post("/users").contentType(MediaType.TEXT_PLAIN).content("segredo"))
            .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.status").value(415));
        mvc.perform(get("/rota-inexistente")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
        String secret = "senha-confidencial";
        var result = mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"" + secret + "\",\"name\":{"))
            .andExpect(status().isBadRequest()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(secret));
        assertEquals(0, users.count());
        assertEquals(0, events.count());
    }

    private long create(String path, String json) throws Exception {
        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated()).andExpect(header().exists("Location"))
            .andExpect(jsonPath("$..password").isEmpty()).andReturn();
        String location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.contains(path + "/"));
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private void deleteAndCheckMissing(String path, long id) throws Exception {
        mvc.perform(delete(path + "/" + id)).andExpect(status().isNoContent())
            .andExpect(content().string(""));
        mvc.perform(get(path + "/" + id)).andExpect(status().isNotFound());
    }

    private User newUser() {
        String email = users.count() == 0 ? "original@example.com" : "original" + users.count() + "@example.com";
        return userService.insert(new User(null, "Voluntario original", email,
            "senha-inicial-forte", 0.0));
    }

    private CleanupEvent newEvent() {
        return events.save(CatalogTestData.event(context, new CleanupEvent(null, "Evento original", "Praia A",
            LocalDateTime.parse("2026-10-15T08:00:00"), "Agendado")));
    }

    private String collectionJson(long userId, long eventId) {
        return """
            {"version":0,"beach":"Praia A","materialType":"Plastico","weightKg":2,
             "registrationDate":"2026-10-15",
             "volunteer":{"id":%d,"name":"Ignorar nome recebido"},
             "event":{"id":%d,"title":"Ignorar titulo recebido"}}
            """.formatted(userId, eventId);
    }
}
