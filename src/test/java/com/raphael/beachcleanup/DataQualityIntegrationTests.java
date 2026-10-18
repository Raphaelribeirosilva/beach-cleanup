package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import com.raphael.beachcleanup.services.*;
import com.raphael.beachcleanup.exceptions.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:dataquality", "spring.jpa.show-sql=false"})
class DataQualityIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired TrashCollectionRepository collections;
    @Autowired CleanupEventRepository events;
    @Autowired UserRepository users;
    @Autowired CatalogEntryRepository entries;
    @Autowired CatalogService catalogs;
    @Autowired CleanupEventService eventService;
    @Autowired TrashCollectionService collectionService;
    @Autowired CollectionDataRules rules;
    @Autowired DataQualityService quality;
    @Autowired CollectedTotalsService totals;
    @Autowired UserService userService;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;
    long beach, otherBeach, material, owner;

    @BeforeEach
    void prepare() {
        CatalogTestData.seed(context);
        collections.deleteAll(); users.deleteAll(); events.deleteAll();
        jdbc.update("delete from tb_weight_conversion_audit");
        var beaches = catalogs.list("beaches");
        beach = beaches.stream().filter(item -> item.name().equals("Praia A")).findFirst().orElseThrow().id();
        otherBeach = beaches.stream().filter(item -> item.name().equals("Praia B")).findFirst().orElseThrow().id();
        material = catalogs.list("materials").getFirst().id();
        owner = userService.insert(new User(null, "Ana", "quality@example.com", "senha-segura-forte", 0.0)).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
            .defaultRequest(get("/").with(user("admin").roles("ADMIN"))).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.0009", "100000.001", "1.2345", "1e1000"})
    void invalidMassDoesNotCreateData(String weight) throws Exception {
        mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(body(weight, rules.today(), beach, null, null)))
            .andExpect(status().isBadRequest());
        assertEquals(0, collections.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "100000", "1.2300"})
    void acceptedBoundariesAreStoredExactly(String weight) throws Exception {
        mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(body(weight, rules.today(), beach, null, null))).andExpect(status().isCreated());
        assertEquals(0, new BigDecimal(weight).compareTo(collections.findAll().getFirst().getWeightKg()));
    }

    @Test
    void decimalTotalsRemainExactAcrossEditsTransfersAndDeletion() throws Exception {
        long first = create("0.1", rules.today(), null);
        long second = create("0.2", rules.today(), null);
        assertEquals(new BigDecimal("0.300"), totals.user(users.findById(owner).orElseThrow()).getTotalCollectedKg());
        mvc.perform(put("/trashCollection/{id}", first).contentType(MediaType.APPLICATION_JSON)
            .content(body("0.125", rules.today(), beach, null, 0L))).andExpect(status().isOk());
        assertEquals(new BigDecimal("0.325"), totals.user(users.findById(owner).orElseThrow()).getTotalCollectedKg());
        var payload = mapper.readTree(body("0.2", rules.today(), beach, null, 0L));
        var other = userService.insert(new User(null, "Bruno", "bruno-quality@example.com", "senha-segura-forte", 0.0));
        ((tools.jackson.databind.node.ObjectNode) payload).set("volunteer", mapper.createObjectNode().put("id", other.getId()));
        mvc.perform(put("/trashCollection/{id}", second).contentType(MediaType.APPLICATION_JSON)
            .content(payload.toString())).andExpect(status().isOk());
        assertEquals(new BigDecimal("0.125"), totals.user(users.findById(owner).orElseThrow()).getTotalCollectedKg());
        assertEquals(new BigDecimal("0.200"), totals.user(other).getTotalCollectedKg());
        mvc.perform(delete("/trashCollection/{id}", first).param("version", collections.findById(first).map(v -> v.getRowVersion().toString()).orElse("0"))).andExpect(status().isNoContent());
        assertEquals(new BigDecimal("0.000"), totals.user(users.findById(owner).orElseThrow()).getTotalCollectedKg());
    }

    @Test
    void datesAllowHistoricalIndependentCollectionsAndRejectFutureOrTooOldValues() throws Exception {
        create("1", CollectionDataRules.FIRST_DATE, null);
        for (LocalDate date : List.of(CollectionDataRules.FIRST_DATE.minusDays(1), rules.today().plusDays(1))) {
            mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
                .content(body("1", date, beach, null, null))).andExpect(status().isBadRequest());
        }
        assertEquals(1, collections.count());
        var historical = eventService.insert(event(CollectionDataRules.FIRST_DATE, beach));
        assertNotNull(historical.getId());
        assertThrows(FieldValidationException.class, () -> eventService.insert(event(CollectionDataRules.FIRST_DATE.minusDays(1), beach)));
        assertNotNull(eventService.insert(event(LocalDate.of(9999, 12, 31), beach)).getId());
        assertThrows(FieldValidationException.class, () -> eventService.insert(event(LocalDate.of(10000, 1, 1), beach)));
    }

    @Test
    void eventBeachAndCalendarDateMustMatchAndEventCannotInvalidateCollections() throws Exception {
        var event = eventService.insert(event(rules.today(), beach));
        for (String json : List.of(body("1", rules.today(), otherBeach, event.getId(), null),
                body("1", rules.today().minusDays(1), beach, event.getId(), null))) {
            mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        }
        long id = create("1", rules.today(), event.getId());
        assertThrows(EventStateConflictException.class, () -> eventService.update(event.getId(), event(rules.today(), otherBeach), 0L));
        assertThrows(EventStateConflictException.class, () -> eventService.update(event.getId(), event(rules.today().minusDays(1), beach), 0L));
        var corrected = event(rules.today(), beach);
        corrected.setTitle("Título corrigido");
        corrected.setDateTime(rules.today().atTime(9, 30));
        eventService.update(event.getId(), corrected, 0L);
        // Corrections in a closed event are permitted, but new links remain forbidden.
        var saved = events.findById(event.getId()).orElseThrow();
        saved.setStatus("Concluido"); events.saveAndFlush(saved);
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content(body("1.125", rules.today(), beach, event.getId(), 0L))).andExpect(status().isOk());
        mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(body("2", rules.today(), beach, event.getId(), null))).andExpect(status().isConflict());
    }

    @Test
    void legacyIssuesArePrivateVisibleAndDisappearOnlyAfterExplicitCorrection() throws Exception {
        var legacy = new TrashCollection(null, null, " ", new BigDecimal("2.500"), rules.today());
        legacy.setVolunteer(users.findById(owner).orElseThrow());
        long id = collections.saveAndFlush(legacy).getId();
        mvc.perform(get("/admin/data-quality").with(user("volunteer").roles("VOLUNTEER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/data-quality/rounding").with(user("volunteer").roles("VOLUNTEER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/data-quality")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/admin/data-quality").param("size", "101")).andExpect(status().isBadRequest());
        assertEquals(2, quality.issues(0, 20).totalElements());
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content(body("2.5", rules.today(), beach, null, 0L))).andExpect(status().isOk());
        assertEquals(0, quality.issues(0, 20).totalElements());
        assertEquals(new BigDecimal("2.500"), totals.user(users.findById(owner).orElseThrow()).getTotalCollectedKg());
    }

    @Test
    void historicalRoundingUsesStringsSoBrowsersDoNotLoseLegacyPrecision() throws Exception {
        jdbc.update("""
            insert into tb_weight_conversion_audit(collection_id,original_weight,rounded_weight,delta_weight)
            values (900,'9007199254740993.125','9007199254740993.125','0.000')
            """);
        mvc.perform(get("/admin/data-quality/rounding")).andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].roundedWeight").value("9007199254740993.125"));
        mvc.perform(get("/admin/data-quality").with(
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyMissingReferencesCanBeRepairedWithoutLosingClosedEventLinks() throws Exception {
        var event = events.saveAndFlush(new CleanupEvent(null, "Legado", null, null, "Concluido"));
        var legacy = new TrashCollection(null, null, null, new BigDecimal("2.500"), null);
        legacy.setEvent(event);
        legacy.setVolunteer(users.findById(owner).orElseThrow());
        var collection = collections.saveAndFlush(legacy);
        var correction = event(rules.today(), beach);
        correction.setStatus("Concluido");
        eventService.update(event.getId(), correction, 0L);
        // Setting the event does not guess or fill the collection's unknown fields.
        var untouched = collections.findById(collection.getId()).orElseThrow();
        assertNull(untouched.getBeachReference());
        assertNull(untouched.getRegistrationDate());
        assertTrue(quality.issues(0, 100).totalElements() > 0);
        mvc.perform(put("/trashCollection/{id}", collection.getId()).contentType(MediaType.APPLICATION_JSON)
            .content(body("2.5", rules.today(), beach, event.getId(), 0L))).andExpect(status().isOk());
        assertEquals(event.getId(), collections.findById(collection.getId()).orElseThrow().getEvent().getId());
        assertEquals(0, quality.issues(0, 100).totalElements());
        assertEquals(new BigDecimal("2.500"), totals.user(users.findById(owner).orElseThrow()).getTotalCollectedKg());
    }

    private CleanupEvent event(LocalDate date, long beachId) {
        var event = new CleanupEvent(null, "Histórico", null, date.atTime(8, 0), "Agendado");
        event.setBeachReference(CatalogEntry.reference(beachId));
        return event;
    }
    private long create(String weight, LocalDate date, Long eventId) throws Exception {
        var response = mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(body(weight, date, beach, eventId, null))).andExpect(status().isCreated()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    }
    private String body(String weight, LocalDate date, long beachId, Long eventId, Long version) {
        var input = mapper.createObjectNode().put("beachId", beachId).put("materialId", material)
            .put("weightKg", new BigDecimal(weight)).put("registrationDate", date.toString());
        input.set("volunteer", mapper.createObjectNode().put("id", owner));
        if (eventId != null) input.set("event", mapper.createObjectNode().put("id", eventId));
        if (version != null) input.put("version", version);
        return input.toString();
    }
}
