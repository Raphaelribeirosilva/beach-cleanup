package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.UserService;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:catalogtests", "spring.jpa.show-sql=false",
    "spring.jpa.open-in-view=false"})
class CatalogIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired TrashCollectionRepository collections;
    @Autowired CleanupEventRepository events;
    @Autowired UserRepository users;
    @Autowired CatalogAliasRepository aliases;
    @Autowired CatalogEntryRepository entries;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    MockMvc mvc;
    long owner;

    @BeforeEach
    void setUp() {
        collections.deleteAll();
        users.deleteAll();
        events.deleteAll();
        aliases.deleteAll();
        jdbc.update("update tb_catalog_entry set merged_into_id=null");
        entries.deleteAll();
        owner = userService.insert(new User(null, "Ana", "ana@example.com", "senha-segura", 0.0)).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
            .defaultRequest(get("/").with(user("admin").roles("ADMIN"))).build();
    }

    @Test
    void catalogReadsArePublicAndOnlyAdministratorsCanManageThem() throws Exception {
        ObjectNode beach = catalog("beaches", "Praia do Morro", "Morro");
        mvc.perform(get("/catalog/beaches").with(anonymous())).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Praia do Morro"));
        mvc.perform(post("/catalog/materials").with(anonymous()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Vidro\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/catalog/materials").with(user(Long.toString(owner)).roles("VOLUNTEER"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Vidro\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/catalog/beaches/" + beach.get("id").asLong())
            .with(user(Long.toString(owner)).roles("VOLUNTEER")).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(beach))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"beaches", "materials"})
    void spellingVariantsResolveToOneIdAndAliasCollisionsCannotCreateDuplicates(String kind) throws Exception {
        ObjectNode item = catalog(kind, "  São   José ", "Nome alternativo");
        assertEquals("São José", item.get("name").asText());
        for (String name : List.of("SAO JOSE", "são josé", " Nome   Alternativo ")) {
            mvc.perform(post("/catalog/" + kind).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapper.createObjectNode().put("name", name))))
                .andExpect(status().isConflict());
        }
        mvc.perform(get("/catalog/" + kind)).andExpect(jsonPath("$.length()").value(1));
        assertEquals(1, entries.count());
        // The same word may legitimately identify a beach and a material.
        catalog(kind.equals("beaches") ? "materials" : "beaches", "São José");
        assertEquals(2, entries.count());
    }

    @Test
    void idsAndKnownLegacyAliasesCreateCanonicalRecordsAndFiltersUseTheirIdentity() throws Exception {
        var beach = catalog("beaches", "Praia do Morro", "Morro");
        var material = catalog("materials", "Plástico", "Plásticos");
        var first = collection(beach.get("id").asLong(), material.get("id").asLong(), null);
        var legacy = mapper.createObjectNode().put("beach", "  MORRO ").put("materialType", "plasticos")
            .put("weightKg", 3.5).put("registrationDate", "2026-10-15");
        legacy.set("volunteer", mapper.createObjectNode().put("id", owner));
        var second = postJson("/trashCollection", legacy);
        assertEquals(first.get("beachId"), second.get("beachId"));
        assertEquals(first.get("materialId"), second.get("materialId"));
        assertEquals("Praia do Morro", second.get("beach").asText());
        assertEquals("Plástico", second.get("materialType").asText());
        mvc.perform(get("/trashCollection/search").param("beachId", beach.get("id").asText())
            .param("materialId", material.get("id").asText())).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/trashCollection/search").param("beach", "mórro").param("materialType", "PLASTICOS"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        var event = event(beach.get("id").asLong());
        assertEquals("Praia do Morro", event.get("location").asText());
        mvc.perform(get("/cleanupEvent/search").with(anonymous()).param("beachId", beach.get("id").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void unknownNamesWrongKindsAndConflictingLabelsCannotBypassCatalogs() throws Exception {
        var beach = catalog("beaches", "Praia A");
        var material = catalog("materials", "Vidro");
        ObjectNode body = collectionInput(beach.get("id").asLong(), material.get("id").asLong(), null);
        body.put("beach", "Outra praia");
        mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
        body.remove("beach");
        body.put("beachId", material.get("id").asLong());
        mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
        body.remove("beachId");
        body.put("beach", "Nome livre desconhecido");
        mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
        assertEquals(0, collections.count());
        assertEquals(2, entries.count());
    }

    @Test
    void mergingBeachesAndMaterialsPreservesRecordsTotalsAndRejectsOpenForms() throws Exception {
        var source = catalog("beaches", "Morro");
        var target = catalog("beaches", "Praia do Morro");
        var material = catalog("materials", "Plastico");
        var canonicalMaterial = catalog("materials", "Plástico reciclável");
        var oldEvent = event(source.get("id").asLong());
        var oldCollection = collection(source.get("id").asLong(), material.get("id").asLong(), oldEvent.get("id").asLong());
        long id = oldCollection.get("id").asLong();
        merge("beaches", source, target);
        var current = read("/trashCollection/" + id);
        assertEquals(target.get("id"), current.get("beachId"));
        assertEquals("Praia do Morro", current.get("beach").asText());
        assertEquals(oldEvent.get("id"), current.get("event").get("id"));
        assertEquals(oldCollection.get("volunteer").get("id"), current.get("volunteer").get("id"));
        assertEquals(2.0, current.get("volunteer").get("totalCollectedKg").asDouble());
        assertTrue(current.get("version").asLong() > oldCollection.get("version").asLong());
        assertTrue(read("/cleanupEvent/" + oldEvent.get("id").asLong()).get("version").asLong() > oldEvent.get("version").asLong());
        mvc.perform(put("/trashCollection/" + id).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(oldCollection)))
            .andExpect(status().isConflict());
        mvc.perform(put("/cleanupEvent/" + oldEvent.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(oldEvent))).andExpect(status().isConflict());
        merge("materials", material, canonicalMaterial);
        current = read("/trashCollection/" + id);
        assertEquals(canonicalMaterial.get("id"), current.get("materialId"));
        assertEquals(2.0, current.get("weightKg").asDouble());
        mvc.perform(get("/catalog/beaches")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/trashCollection/search").param("beach", "Morro").param("materialType", "Plastico"))
            .andExpect(jsonPath("$.totalElements").value(1));
        assertEquals(1, jdbc.queryForObject("select count(distinct beach_id) from tb_trash_collection", Integer.class));
    }

    @Test
    void renamePreservesAliasesAndVersionChecksRejectStaleCatalogChanges() throws Exception {
        var beach = catalog("beaches", "Praia A");
        var material = catalog("materials", "Vidro");
        var collected = collection(beach.get("id").asLong(), material.get("id").asLong(), null);
        var rename = beach.deepCopy();
        rename.put("name", "Praia A - Cidade");
        var updated = putJson("/catalog/beaches/" + beach.get("id").asLong(), rename);
        assertTrue(updated.get("version").asLong() > beach.get("version").asLong());
        mvc.perform(put("/catalog/beaches/" + beach.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(rename))).andExpect(status().isConflict());
        mvc.perform(get("/trashCollection/search").param("beach", "Praia A"))
            .andExpect(jsonPath("$.content[0].beach").value("Praia A - Cidade"));
        mvc.perform(put("/trashCollection/" + collected.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(collected))).andExpect(status().isConflict());
        var other = catalog("beaches", "Outra praia");
        mvc.perform(post("/catalog/beaches/" + other.get("id").asLong() + "/merge").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapper.createObjectNode().put("targetId", beach.get("id").asLong())
                .put("version", other.get("version").asLong()).put("targetVersion", beach.get("version").asLong()))))
            .andExpect(status().isConflict());
        mvc.perform(get("/catalog/beaches")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void simultaneousEquivalentCreatesProduceOneCatalogEntry() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createAfter(start, "São José"));
            var second = executor.submit(() -> createAfter(start, "SAO JOSE"));
            start.countDown();
            var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(code -> code == 201).count());
            assertEquals(1, results.stream().filter(code -> code == 409).count());
        }
        assertEquals(1, entries.count());
    }

    private int createAfter(CountDownLatch start, String name) throws Exception {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return mvc.perform(post("/catalog/beaches").with(user("admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapper.createObjectNode().put("name", name))))
            .andReturn().getResponse().getStatus();
    }

    @Test
    void aCachedCatalogEntryCannotReintroduceAMergedIdInANewCollection() throws Exception {
        var source = catalog("beaches", "Morro");
        var target = catalog("beaches", "Praia do Morro");
        var material = catalog("materials", "Vidro");
        CountDownLatch cached = new CountDownLatch(1);
        CountDownLatch merged = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx -> {
                entries.findById(source.get("id").asLong()).orElseThrow().getName();
                cached.countDown();
                try {
                    assertTrue(merged.await(15, TimeUnit.SECONDS));
                    int status = mvc.perform(post("/trashCollection").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                            collectionInput(source.get("id").asLong(), material.get("id").asLong(), null))))
                        .andReturn().getResponse().getStatus();
                    tx.setRollbackOnly();
                    return status;
                } catch (Exception e) { throw new AssertionError(e); }
            }));
            try {
                assertTrue(cached.await(15, TimeUnit.SECONDS));
                merge("beaches", source, target);
            } finally { merged.countDown(); }
            assertEquals(409, pending.get(30, TimeUnit.SECONDS));
        }
        assertEquals(0, collections.count());
    }
    private ObjectNode catalog(String kind, String name, String... aliases) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("name", name);
        var values = body.putArray("aliases");
        for (String alias : aliases) values.add(alias);
        return postJson("/catalog/" + kind, body);
    }
    private ObjectNode event(long beach) throws Exception {
        return postJson("/cleanupEvent", mapper.createObjectNode().put("beachId", beach).put("title", "Mutirão")
            .put("dateTime", "2026-10-15T08:00:00").put("status", "Agendado"));
    }
    private ObjectNode collectionInput(long beach, long material, Long event) {
        var body = mapper.createObjectNode().put("beachId", beach).put("materialId", material)
            .put("weightKg", 2.0).put("registrationDate", "2026-10-15");
        body.set("volunteer", mapper.createObjectNode().put("id", owner));
        if (event != null) body.set("event", mapper.createObjectNode().put("id", event));
        return body;
    }
    private ObjectNode collection(long beach, long material, Long event) throws Exception {
        return postJson("/trashCollection", collectionInput(beach, material, event));
    }
    private ObjectNode merge(String kind, ObjectNode source, ObjectNode target) throws Exception {
        var input = mapper.createObjectNode().put("targetId", target.get("id").asLong())
            .put("version", source.get("version").asLong()).put("targetVersion", target.get("version").asLong());
        var response = mvc.perform(post("/catalog/" + kind + "/" + source.get("id").asLong() + "/merge")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)))
            .andExpect(status().isOk()).andReturn().getResponse();
        return (ObjectNode) mapper.readTree(response.getContentAsString());
    }
    private ObjectNode postJson(String path, ObjectNode input) throws Exception {
        var response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)))
            .andExpect(status().isCreated()).andReturn().getResponse();
        return (ObjectNode) mapper.readTree(response.getContentAsString());
    }
    private ObjectNode putJson(String path, ObjectNode input) throws Exception {
        var response = mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)))
            .andExpect(status().isOk()).andReturn().getResponse();
        return (ObjectNode) mapper.readTree(response.getContentAsString());
    }
    private ObjectNode read(String path) throws Exception {
        return (ObjectNode) mapper.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
