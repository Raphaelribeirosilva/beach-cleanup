package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.raphael.beachcleanup.entities.*;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:audit", "spring.jpa.show-sql=false"})
class AuditIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired AuditEntryRepository audits;
    @Autowired UserRepository users;
    @Autowired CleanupEventRepository events;
    @Autowired TrashCollectionRepository collections;
    @Autowired UserService userService;
    @Autowired AuditService audit;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;
    User admin, owner, other;
    CleanupEvent event;

    @BeforeEach void setup() {
        CatalogTestData.seed(context);
        audits.deleteAll(); collections.deleteAll(); users.deleteAll(); events.deleteAll();
        admin = account("admin"); owner = account("owner"); other = account("other");
        event = events.saveAndFlush(CatalogTestData.event(context, new CleanupEvent(null, "Histórico", "Praia A",
            LocalDateTime.parse("2026-01-10T09:00:00"), "Agendado")));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
            .defaultRequest(get("/").with(user(admin.getId().toString()).roles("ADMIN"))).build();
    }
    User account(String name) { return userService.insert(new User(null, name, name+"@example.com", "senha-inicial-forte", 0.0)); }
    String input(Long ownerId, long version) {
        return """
            {"version":%d,"beach":"Praia A","materialType":"Plastico","weightKg":2.125,
             "registrationDate":"2026-01-10","volunteer":{"id":%d},"event":{"id":%d}}
            """.formatted(version, ownerId, event.getId());
    }
    long create() throws Exception {
        return mapper.readTree(mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(input(owner.getId(),0))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    @Test void transferRecordsNumericAuthorTimeAndLinksWithoutSecretsAndFiltersAreRestricted() throws Exception {
        long id = create(); Instant before = Instant.now();
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON).content(input(other.getId(),0)))
            .andExpect(status().isOk());
        var entry = audits.findAll().stream().filter(a -> a.action.equals("TRANSFER")).findFirst().orElseThrow();
        assertEquals(admin.getId(), entry.actorId); assertEquals("ADMIN", entry.actorRole);
        assertEquals(owner.getId(), entry.previousUserId); assertEquals(other.getId(), entry.nextUserId);
        assertEquals(event.getId(), entry.previousEventId); assertFalse(entry.occurredAt.isBefore(before));
        var json = mvc.perform(get("/admin/audit").param("resource", "COLLECTION").param("resourceId", ""+id)
            .param("actorId", admin.getId().toString()).param("action", "TRANSFER").param("from", before.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andReturn().getResponse().getContentAsString();
        for (String secret : new String[]{"@example.com", "password", "token", "senha-inicial", "Authorization"}) assertFalse(json.contains(secret));
        mvc.perform(get("/admin/audit").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/audit").with(user(owner.getId().toString()).roles("VOLUNTEER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/audit").param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/audit").param("action","DELETE")).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void deletionRequiresVisibleVersionAndPreservesHistoricalUsersEventsAndEvidence() throws Exception {
        long id = create();
        mvc.perform(delete("/trashCollection/{id}",id)).andExpect(status().isBadRequest());
        mvc.perform(put("/trashCollection/{id}",id).contentType(MediaType.APPLICATION_JSON).content(input(other.getId(),0))).andExpect(status().isOk());
        mvc.perform(delete("/trashCollection/{id}",id).param("version","0")).andExpect(status().isConflict());
        assertEquals(0, audits.findAll().stream().filter(a -> a.action.equals("DELETE")).count());
        mvc.perform(delete("/trashCollection/{id}",id).param("version","1")).andExpect(status().isNoContent());
        for (User u : new User[]{owner,other}) mvc.perform(delete("/users/{id}",u.getId()).param("version","0")).andExpect(status().isConflict());
        mvc.perform(delete("/cleanupEvent/{id}",event.getId()).param("version","0")).andExpect(status().isConflict());
        assertEquals(3, audits.count()); assertTrue(events.existsById(event.getId()));
        mvc.perform(get("/reports/impact")).andExpect(jsonPath("$.totals.weightKg").value(0));
        var empty = account("empty");
        mvc.perform(delete("/users/{id}",empty.getId())).andExpect(status().isBadRequest());
        mvc.perform(delete("/users/{id}",empty.getId()).param("version","1")).andExpect(status().isConflict());
        mvc.perform(delete("/users/{id}",empty.getId()).param("version","0")).andExpect(status().isNoContent());
        var emptyEvent = events.saveAndFlush(CatalogTestData.event(context, new CleanupEvent(null, "Vazio", "Praia A",
            LocalDateTime.parse("2026-01-10T09:00:00"), "Agendado")));
        mvc.perform(delete("/cleanupEvent/{id}",emptyEvent.getId())).andExpect(status().isBadRequest());
        mvc.perform(delete("/cleanupEvent/{id}",emptyEvent.getId()).param("version","1")).andExpect(status().isConflict());
        mvc.perform(delete("/cleanupEvent/{id}",emptyEvent.getId()).param("version","0")).andExpect(status().isNoContent());
        mvc.perform(get("/admin/audit").param("resource","EVENT").param("resourceId",emptyEvent.getId().toString()))
            .andExpect(jsonPath("$.content[0].action").value("DELETE"));
    }

    @Test void stateChangesAndCatalogMergesRecordControlledBeforeAfterAndRejectedChangesLeaveNoAudit() throws Exception {
        String body = """
            {"version":0,"title":"Histórico","location":"Praia A","dateTime":"2026-01-10T09:00:00","status":"Em andamento"}
            """;
        mvc.perform(put("/cleanupEvent/{id}",event.getId()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(put("/cleanupEvent/{id}",event.getId()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        var state = audits.findAll().getFirst(); assertEquals("Agendado",state.previousState); assertEquals("Em andamento",state.nextState);
        mvc.perform(delete("/cleanupEvent/{id}",event.getId()).param("version","1")).andExpect(status().isConflict());
        long source = catalog("Audit source"), target = catalog("Audit target");
        mvc.perform(post("/catalog/materials/{id}/merge",source).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":0,\"targetVersion\":0,\"targetId\":"+target+"}")).andExpect(status().isOk());
        var merge = audits.findAll().stream().filter(a -> a.action.equals("MERGE")).findFirst().orElseThrow();
        assertEquals(source,merge.resourceId); assertEquals(target,merge.targetId); assertEquals("MATERIAL",merge.resource);
        assertEquals(2,audits.count());
    }
    long catalog(String name) throws Exception {
        return mapper.readTree(mvc.perform(post("/catalog/materials").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\""+name+"\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    @Test void rollbackRemovesEvidenceAndRegistrationsRemainHistoricalAfterCancellation() throws Exception {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(tx -> {
            audit.record("EVENT", event.getId(), "STATE_CHANGE",null,null,null,null,null,"Agendado","Pausado");
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0,audits.count());
        mvc.perform(put("/users/me/events/{id}",event.getId()).with(user(owner.getId().toString()).roles("VOLUNTEER"))).andExpect(status().isNoContent());
        mvc.perform(delete("/users/me/events/{id}",event.getId()).with(user(owner.getId().toString()).roles("VOLUNTEER"))).andExpect(status().isNoContent());
        mvc.perform(delete("/users/{id}",owner.getId()).param("version",users.findById(owner.getId()).orElseThrow().getRowVersion().toString())).andExpect(status().isConflict());
        mvc.perform(delete("/cleanupEvent/{id}",event.getId()).param("version","0")).andExpect(status().isConflict());
        assertEquals(2,audits.count());
    }
}
