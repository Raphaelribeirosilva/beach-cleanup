package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.services.UserService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:eventstatetests", "spring.jpa.show-sql=false"})
class EventStateIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired CleanupEventRepository events;
    @Autowired UserRepository users;
    @Autowired TrashCollectionRepository collections;
    @Autowired UserService userService;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;

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
    void onlyScheduledEventsCanBeCreatedAndUnknownStatusIsRejected() throws Exception {
        mvc.perform(post("/cleanupEvent").contentType(MediaType.APPLICATION_JSON).content(eventJson(" agendado ")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("Agendado"));
        for (String state : new String[] {"Em andamento", "Pausado", "Concluido", "Finalizado", "Cancelado"}) {
            mvc.perform(post("/cleanupEvent").contentType(MediaType.APPLICATION_JSON).content(eventJson(state)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        }
        mvc.perform(post("/cleanupEvent").contentType(MediaType.APPLICATION_JSON).content(eventJson("qualquer coisa")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].fieldName").value("status"));
        assertEquals(1, events.count());
    }

    @Test
    void transitionsAreValidatedWithoutPartiallyUpdatingRejectedRequests() throws Exception {
        // Expected matrix is independent of the enum implementation; includes unchanged status.
        String[] states = {"Agendado", "Em andamento", "Pausado", "Concluido", "Cancelado"};
        boolean[][] allowed = {
            {true, true, true, false, true},
            {false, true, true, true, true},
            {true, true, true, false, true},
            {false, false, false, true, false},
            {false, false, false, false, true}
        };
        for (int from = 0; from < states.length; from++) {
            for (int to = 0; to < states.length; to++) {
                var event = event(states[from]);
                mvc.perform(put("/cleanupEvent/{id}", event.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(eventJson(states[to])))
                    .andExpect(status().is(allowed[from][to] ? 200 : 409));
                var saved = events.findById(event.getId()).orElseThrow();
                assertEquals(allowed[from][to] ? states[to] : states[from], saved.getStatus());
                assertEquals(allowed[from][to] ? "Novo titulo" : "Original", saved.getTitle());
            }
        }
        var event = event("Em andamento");
        mvc.perform(put("/cleanupEvent/{id}", event.getId()).contentType(MediaType.APPLICATION_JSON)
            .content(eventJson("Concluído"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Concluido"));
    }

    @Test
    void pausedAndTerminalEventsRejectNewRegistrationsAndCollections() throws Exception {
        User volunteer = userService.insert(new User(null, "Ana", "ana@example.com", "senha-segura-forte", 0.0));
        for (String state : new String[] {"Pausado", "Concluido", "Cancelado"}) {
            long id = event(state).getId();
            mvc.perform(put("/users/me/events/{id}", id)
                .with(user(volunteer.getId().toString()).roles("VOLUNTEER")))
                .andExpect(status().isConflict());
            mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
                .content(collectionJson(volunteer.getId(), id, 2.0)))
                .andExpect(status().isConflict());
            mvc.perform(delete("/users/me/events/{id}", id)
                .with(user(volunteer.getId().toString()).roles("VOLUNTEER")))
                .andExpect(status().isNoContent());
        }
        assertEquals(0, collections.count());
    }

    @Test
    void closingPreservesRecordsAllowsCorrectionsAndPreventsNewLinks() throws Exception {
        User volunteer = userService.insert(new User(null, "Ana", "ana@example.com", "senha-segura-forte", 0.0));
        long id = event("Em andamento").getId();
        mvc.perform(put("/users/me/events/{id}", id).with(user(volunteer.getId().toString()).roles("VOLUNTEER")))
            .andExpect(status().isNoContent());
        var created = mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(volunteer.getId(), id, 2.0))).andExpect(status().isCreated()).andReturn();
        long collection = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(put("/cleanupEvent/{id}", id).contentType(MediaType.APPLICATION_JSON).content(eventJson(" finalizado ")))
            .andExpect(status().isOk());
        mvc.perform(get("/users/me/events").with(user(volunteer.getId().toString()).roles("VOLUNTEER")))
            .andExpect(jsonPath("$[0].status").value("Concluido"));
        mvc.perform(put("/users/me/events/{id}", id).with(user(volunteer.getId().toString()).roles("VOLUNTEER")))
            .andExpect(status().isNoContent());
        mvc.perform(put("/trashCollection/{id}", collection).contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(volunteer.getId(), id, 3.5)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.volunteer.totalCollectedKg").value(3.5));
        mvc.perform(get("/reports/impact").param("eventId", Long.toString(id)).with(anonymous()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totals.weightKg").value(3.5))
            .andExpect(jsonPath("$.totals.collections").value(1));
        long other = event("Cancelado").getId();
        mvc.perform(put("/trashCollection/{id}", collection).contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(volunteer.getId(), other, 4.0, 1)))
            .andExpect(status().isConflict());
        assertEquals(id, collections.findById(collection).orElseThrow().getEvent().getId());
        mvc.perform(delete("/users/me/events/{id}", id).with(user(volunteer.getId().toString()).roles("VOLUNTEER")))
            .andExpect(status().isNoContent());
        mvc.perform(get("/users/{id}", volunteer.getId()))
            .andExpect(jsonPath("$.totalCollectedKg").value(3.5));
        mvc.perform(delete("/trashCollection/{id}", collection).param("version", collections.findById(collection).map(v -> v.getRowVersion().toString()).orElse("0"))).andExpect(status().isNoContent());
    }

    @Test
    void finalizadoAliasRequiresAdminAndRetainsCanonicalSearchAndTerminalRules() throws Exception {
        var event = event("Em andamento");
        long id = event.getId();
        mvc.perform(put("/cleanupEvent/{id}", id).with(anonymous())
            .contentType(MediaType.APPLICATION_JSON).content(eventJson("Finalizado")))
            .andExpect(status().isUnauthorized());
        User volunteer = userService.insert(new User(null, "Bia", "bia@example.com", "senha-segura-forte", 0.0));
        mvc.perform(put("/cleanupEvent/{id}", id).with(user(volunteer.getId().toString()).roles("VOLUNTEER"))
            .contentType(MediaType.APPLICATION_JSON).content(eventJson("Finalizado")))
            .andExpect(status().isForbidden());
        assertEquals("Em andamento", events.findById(id).orElseThrow().getStatus());
        mvc.perform(put("/cleanupEvent/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content(eventJson(" FINALIZADO "))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Concluido"));
        for (String query : new String[]{"Finalizado", "Concluido", "Concluído"}) {
            mvc.perform(get("/cleanupEvent/search").param("status", query).with(anonymous()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("Concluido"));
        }
        mvc.perform(get("/cleanupEvent/{id}", id).with(anonymous())).andExpect(status().isOk());
        mvc.perform(put("/users/me/events/{id}", id).with(user(volunteer.getId().toString()).roles("VOLUNTEER")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Evento finalizado")));
        mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(volunteer.getId(), id, 1.0))).andExpect(status().isConflict());
        var scheduled = event("Agendado");
        mvc.perform(put("/cleanupEvent/{id}", scheduled.getId()).contentType(MediaType.APPLICATION_JSON)
            .content(eventJson("Finalizado"))).andExpect(status().isConflict());
        String reopen = eventJson("Em andamento").replace("\"version\":0", "\"version\":" + events.findById(id).orElseThrow().getRowVersion());
        mvc.perform(put("/cleanupEvent/{id}", id).contentType(MediaType.APPLICATION_JSON).content(reopen))
            .andExpect(status().isConflict());
        assertEquals("Concluido", events.findById(id).orElseThrow().getStatus());
    }

    private CleanupEvent event(String state) {
        return events.save(CatalogTestData.event(context, new CleanupEvent(null, "Original", "Praia A", LocalDateTime.parse("2026-10-15T08:00:00"), state)));
    }

    private String eventJson(String state) {
        return """
            {"version":0,"title":"Novo titulo","location":"Praia A","dateTime":"2026-10-15T09:00:00","status":"%s"}
            """.formatted(state);
    }

    private String collectionJson(long userId, long eventId, double weight) {
        return collectionJson(userId, eventId, weight, 0);
    }

    private String collectionJson(long userId, long eventId, double weight, long version) {
        return """
            {"version":%d,"beach":"Praia A","materialType":"Vidro","weightKg":%s,"registrationDate":"2026-10-15",
             "volunteer":{"id":%d},"event":{"id":%d}}
            """.formatted(version, weight, userId, eventId);
    }
}
