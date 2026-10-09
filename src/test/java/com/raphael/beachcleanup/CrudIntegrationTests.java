package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.Volunteer;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.VolunteerRepository;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:crudtests",
    "spring.jpa.show-sql=false"
})
class CrudIntegrationTests {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private VolunteerRepository volunteers;
    @Autowired
    private CleanupEventRepository events;
    @Autowired
    private TrashCollectionRepository collections;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        collections.deleteAll();
        volunteers.deleteAll();
        events.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void volunteerCrudUpdatesNameAndEmailAndPreservesManagedFields() throws Exception {
        long id = create("/volunteers", """
            {"name":"Ana","email":"ana@example.com","password":"senha-inicial"}
            """);
        assertEquals("senha-inicial", volunteers.findById(id).orElseThrow().getPassword());
        assertEquals(0.0, volunteers.findById(id).orElseThrow().getTotalTrashCollecting());
        mvc.perform(get("/volunteers"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/volunteers/{id}", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Ana"));
        mvc.perform(put("/volunteers/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"id":999999,"name":"Ana Silva","email":"ana.silva@example.com",
                 "password":"outra-senha","totalTrashCollecting":500}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.name").value("Ana Silva"))
            .andExpect(jsonPath("$.email").value("ana.silva@example.com"));
        Volunteer saved = volunteers.findById(id).orElseThrow();
        assertEquals("senha-inicial", saved.getPassword());
        assertEquals(0.0, saved.getTotalTrashCollecting());
        deleteAndCheckMissing("/volunteers", id);
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
            .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/cleanupEvent/{id}", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Mutirao"));
        mvc.perform(put("/cleanupEvent/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"id":999999,"title":"Novo titulo","location":"Praia B",
                 "dateTime":"2026-10-20T09:00:00","status":"Concluido"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.title").value("Novo titulo"));
        CleanupEvent saved = events.findById(id).orElseThrow();
        assertEquals("Praia B", saved.getLocation());
        assertEquals(LocalDateTime.parse("2026-10-20T09:00:00"), saved.getDateTime());
        assertEquals("Concluido", saved.getStatus());
        deleteAndCheckMissing("/cleanupEvent", id);
    }

    @Test
    void collectionCrudResolvesReferencesAndUpdatesDateAndAssociations() throws Exception {
        long volunteerId = newVolunteer().getId();
        long eventId = newEvent().getId();
        long id = create("/trashCollection", collectionJson(volunteerId, eventId));
        mvc.perform(get("/trashCollection")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/trashCollection/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.volunteer.name").value("Voluntario original"))
            .andExpect(jsonPath("$.event.title").value("Evento original"));
        long anotherVolunteer = newVolunteer().getId();
        long anotherEvent = newEvent().getId();
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"id":999999,"beach":"Praia B","materialType":"Vidro","weithKg":3.5,
                 "regitrationDate":"2026-10-20","volunteer":{"id":%d},"event":{"id":%d}}
                """.formatted(anotherVolunteer, anotherEvent)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.volunteer.id").value(anotherVolunteer))
            .andExpect(jsonPath("$.event.id").value(anotherEvent));
        var saved = collections.findById(id).orElseThrow();
        assertEquals("Praia B", saved.getBeach());
        assertEquals("Vidro", saved.getMaterialType());
        assertEquals(3.5, saved.getWeithKg());
        assertEquals(LocalDate.parse("2026-10-20"), saved.getRegitrationDate());
        deleteAndCheckMissing("/trashCollection", id);
    }

    @Test
    void missingIdsReturn404ForReadUpdateAndDeleteAcrossAllResources() throws Exception {
        for (String path : new String[] {"/volunteers", "/cleanupEvent", "/trashCollection"}) {
            mvc.perform(get(path + "/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
            mvc.perform(put(path + "/999999").contentType(MediaType.APPLICATION_JSON).content("{}"))
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
        long volunteerId = newVolunteer().getId();
        mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(volunteerId, 999999))).andExpect(status().isNotFound());
        assertEquals(0, collections.count());
    }

    @Test
    void collectionUpdateWithMissingReferenceLeavesPersistedDataIntact() throws Exception {
        long volunteerId = newVolunteer().getId();
        long eventId = newEvent().getId();
        long id = create("/trashCollection", collectionJson(volunteerId, eventId));
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(999999, eventId))).andExpect(status().isNotFound());
        var saved = collections.findById(id).orElseThrow();
        assertEquals(volunteerId, saved.getVolunteer().getId());
        assertEquals(eventId, saved.getEvent().getId());
        assertEquals(2.0, saved.getWeithKg());
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
        assertEquals(0, volunteers.count());
        assertEquals(0, events.count());
    }

    @Test
    void linkedResourcesReturn409AndCanBeDeletedAfterCollectionRemoval() throws Exception {
        long volunteerId = newVolunteer().getId();
        long eventId = newEvent().getId();
        long collectionId = create("/trashCollection", collectionJson(volunteerId, eventId));
        mvc.perform(delete("/volunteers/{id}", volunteerId))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(delete("/cleanupEvent/{id}", eventId))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertTrue(volunteers.existsById(volunteerId));
        assertTrue(events.existsById(eventId));
        assertTrue(collections.existsById(collectionId));
        deleteAndCheckMissing("/trashCollection", collectionId);
        deleteAndCheckMissing("/volunteers", volunteerId);
        deleteAndCheckMissing("/cleanupEvent", eventId);
    }

    @Test
    void collectionSupportsNullReferencesAndCanRemoveExistingReferences() throws Exception {
        long id = create("/trashCollection",
            collectionJson(newVolunteer().getId(), newEvent().getId()));
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"beach":"Praia A","materialType":"Plastico","weithKg":1,
                 "regitrationDate":"2026-10-21","volunteer":null,"event":null}
                """)).andExpect(status().isOk());
        var saved = collections.findById(id).orElseThrow();
        assertNull(saved.getVolunteer());
        assertNull(saved.getEvent());
        create("/trashCollection", """
            {"id":%d,"beach":"Praia A","materialType":"Vidro","weithKg":1,
             "regitrationDate":"2026-10-21"}
            """.formatted(id));
        assertEquals(2, collections.count());
        assertEquals("Plastico", collections.findById(id).orElseThrow().getMaterialType());
    }

    private long create(String path, String json) throws Exception {
        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn();
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

    private Volunteer newVolunteer() {
        return volunteers.save(new Volunteer(null, "Voluntario original", "original@example.com",
            "senha-inicial", 0.0));
    }

    private CleanupEvent newEvent() {
        return events.save(new CleanupEvent(null, "Evento original", "Praia A",
            LocalDateTime.parse("2026-10-15T08:00:00"), "Agendado"));
    }

    private String collectionJson(long volunteerId, long eventId) {
        return """
            {"beach":"Praia A","materialType":"Plastico","weithKg":2,
             "regitrationDate":"2026-10-15",
             "volunteer":{"id":%d,"name":"Ignorar nome recebido"},
             "event":{"id":%d,"title":"Ignorar titulo recebido"}}
            """.formatted(volunteerId, eventId);
    }
}
