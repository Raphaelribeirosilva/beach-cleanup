package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.raphael.beachcleanup.entities.*;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:impactreports", "spring.jpa.show-sql=false"})
class ImpactReportIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired TrashCollectionRepository collections;
    @Autowired CleanupEventRepository events;
    @Autowired CatalogEntryRepository catalogs;
    @Autowired CatalogService catalogService;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;
    User ana, bruno, registeredOnly;
    CatalogEntry beach, otherBeach, material, otherMaterial;
    CleanupEvent event;
    TrashCollection first;

    @BeforeEach void setup() {
        CatalogTestData.seed(context);
        collections.deleteAll(); users.deleteAll(); events.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ana = account("ana"); bruno = account("bruno"); registeredOnly = account("inscrito");
        beach = entry("beaches", "Praia A"); otherBeach = entry("beaches", "Praia B");
        material = entry("materials", "Vidro"); otherMaterial = entry("materials", "Metal");
        event = new CleanupEvent(null, "Mutirão", "Praia A", LocalDateTime.parse("2026-01-10T09:00:00"), "Agendado");
        event.setBeachReference(beach); event = events.saveAndFlush(event);
        jdbc.update("insert into tb_event_volunteer(event_id, volunteer_id) values (?, ?)", event.getId(), registeredOnly.getId());
        first = collection(ana, beach, material, event, "1.125", "2026-01-10");
        collection(ana, beach, otherMaterial, event, "2.250", "2026-01-10");
        collection(bruno, otherBeach, material, null, "3.001", "2026-01-11");
        collection(null, otherBeach, material, null, "0.001", "2026-01-11");
        collection(ana, beach, material, null, "9.999", "2025-12-31");
    }

    @Test void publicTotalsReconcileForEveryGroupingWithoutPersonalData() throws Exception {
        for (String group : new String[]{"beach", "material", "event", "period"}) {
            var response = mvc.perform(get("/reports/impact").param("from", "2026-01-10").param("to", "2026-01-11").param("groupBy", group))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.weightKg").value(6.377))
                .andExpect(jsonPath("$.totals.collections").value(4)).andExpect(jsonPath("$.totals.collectors").value(2)).andReturn();
            var json = mapper.readTree(response.getResponse().getContentAsString());
            BigDecimal sum = BigDecimal.ZERO; long count = 0;
            for (var row : json.get("rows")) { sum = sum.add(row.get("weightKg").decimalValue()); count += row.get("collections").asLong(); }
            assertEquals(0, sum.compareTo(new BigDecimal("6.377"))); assertEquals(4, count);
            assertFalse(json.toString().contains("@example.com")); assertFalse(json.toString().contains("volunteerId"));
            if (group.equals("event")) assertTrue(json.toString().contains("Sem evento"));
        }
    }

    @Test void catalogEventAndInclusiveDateFiltersIntersect() throws Exception {
        mvc.perform(get("/reports/impact").param("from", "2026-01-10").param("to", "2026-01-10")
            .param("beachId", beach.getId().toString()).param("materialId", material.getId().toString()).param("eventId", event.getId().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totals.weightKg").value(1.125))
            .andExpect(jsonPath("$.totals.collections").value(1)).andExpect(jsonPath("$.totals.collectors").value(1));
        mvc.perform(get("/reports/impact").param("beachId", "999999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totals.weightKg").value(0))
            .andExpect(jsonPath("$.totals.collections").value(0)).andExpect(jsonPath("$.totals.collectors").value(0))
            .andExpect(jsonPath("$.rows").isEmpty());
        for (String query : new String[]{"from=2026-02-01&to=2026-01-01", "groupBy=owner", "beachId=-1", "from=invalid"})
            mvc.perform(get("/reports/impact?" + query)).andExpect(status().isBadRequest());
    }

    @Test void registrationsAreRestrictedAndIndependentOfCollections() throws Exception {
        mvc.perform(get("/reports/participation")).andExpect(status().isUnauthorized());
        mvc.perform(get("/reports/participation").with(user(ana.getId().toString()).roles("VOLUNTEER"))).andExpect(status().isForbidden());
        mvc.perform(get("/reports/participation").with(user("admin").roles("ADMIN"))
            .param("from", "2026-01-10").param("to", "2026-01-10").param("beachId", beach.getId().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].registeredParticipants").value(1));
        mvc.perform(get("/reports/impact").param("eventId", event.getId().toString()))
            .andExpect(jsonPath("$.totals.collectors").value(1)).andExpect(jsonPath("$.totals.collections").value(2));
        jdbc.update("delete from tb_event_volunteer");
        mvc.perform(get("/reports/participation").with(user("admin").roles("ADMIN")))
            .andExpect(jsonPath("$[0].registeredParticipants").value(0));
        mvc.perform(get("/reports/impact").param("eventId", event.getId().toString()))
            .andExpect(jsonPath("$.totals.weightKg").value(3.375));
    }

    @Test void mutationsAndStaleFormsReconcileWithReportAndPersonalTotals() throws Exception {
        String input = """
            {"beachId":%d,"materialId":%d,"weightKg":4.001,"registrationDate":"2026-01-10","version":0}
            """.formatted(beach.getId(), material.getId());
        var owner = user(ana.getId().toString()).roles("VOLUNTEER");
        mvc.perform(put("/trashCollection/{id}", first.getId()).with(owner).contentType(MediaType.APPLICATION_JSON).content(input))
            .andExpect(status().isOk());
        mvc.perform(put("/trashCollection/{id}", first.getId()).with(owner).contentType(MediaType.APPLICATION_JSON).content(input))
            .andExpect(status().isConflict());
        mvc.perform(get("/reports/impact").param("from", "2026-01-01"))
            .andExpect(jsonPath("$.totals.weightKg").value(9.253));
        mvc.perform(delete("/trashCollection/{id}", first.getId()).with(owner)).andExpect(status().isNoContent());
        mvc.perform(get("/reports/impact").param("from", "2026-01-01"))
            .andExpect(jsonPath("$.totals.weightKg").value(5.252)).andExpect(jsonPath("$.totals.collections").value(3));
        mvc.perform(get("/users/{id}", ana.getId()).with(owner))
            .andExpect(jsonPath("$.totalCollectedKg").value(12.249));
    }

    @Test void volunteerCannotModifyOtherAccountsOrCollections() throws Exception {
        var stranger = user(bruno.getId().toString()).roles("VOLUNTEER");
        mvc.perform(delete("/trashCollection/{id}", first.getId()).with(stranger)).andExpect(status().isForbidden());
        mvc.perform(put("/trashCollection/{id}", first.getId()).with(stranger).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"beachId":%d,"materialId":%d,"weightKg":1,"registrationDate":"2026-01-10","version":0}
                """.formatted(beach.getId(), material.getId()))).andExpect(status().isForbidden());
        mvc.perform(put("/users/{id}", ana.getId()).with(stranger).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Outro\",\"email\":\"outro@example.com\",\"version\":0}"))
            .andExpect(status().isForbidden());
        assertTrue(collections.existsById(first.getId()));
    }

    private User account(String name) { return userService.insert(new User(null, name, name + "@example.com", "senha-inicial-forte", 0.0)); }
    private CatalogEntry entry(String kind, String name) {
        var id = catalogService.list(kind).stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow().id();
        return catalogs.findById(id).orElseThrow();
    }
    private TrashCollection collection(User owner, CatalogEntry beach, CatalogEntry material, CleanupEvent event, String weight, String day) {
        var value = new TrashCollection(null, beach.getName(), material.getName(), new BigDecimal(weight), LocalDate.parse(day));
        value.setVolunteer(owner); value.setBeachReference(beach); value.setMaterialReference(material); value.setEvent(event);
        return collections.saveAndFlush(value);
    }
}
