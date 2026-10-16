package com.raphael.beachcleanup;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.services.UserService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:collectedtotaltests",
    "spring.jpa.show-sql=false"
})
class CollectedTotalsIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired TrashCollectionRepository collections;
    @Autowired CleanupEventRepository events;
    @Autowired UserService userService;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
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
    void totalsFollowCreationWeightChangesTransfersAndDeletion() throws Exception {
        long ana = account("ana").getId();
        long bruno = account("bruno").getId();
        assertTotal(ana, 0.0);
        assertTotal(bruno, 0.0);
        long first = create(ana, 2.0, 2.0);
        long second = create(ana, 3.5, 5.5);
        assertTotal(ana, 5.5);
        mvc.perform(get("/trashCollection/{id}", first))
            .andExpect(jsonPath("$.volunteer.totalCollectedKg").value(5.5));
        mvc.perform(get("/trashCollection"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].volunteer.totalCollectedKg").value(contains(5.5, 5.5)));
        mvc.perform(get("/users"))
            .andExpect(jsonPath("$[?(@.id == %d)].totalCollectedKg".formatted(ana)).value(contains(5.5)))
            .andExpect(jsonPath("$[?(@.id == %d)].totalCollectedKg".formatted(bruno)).value(contains(0.0)));

        mvc.perform(put("/trashCollection/{id}", first).contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(ana, 4.0)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.volunteer.totalCollectedKg").value(7.5));
        assertTotal(ana, 7.5);
        mvc.perform(put("/trashCollection/{id}", first).contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(bruno, 4.0, 1)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.volunteer.totalCollectedKg").value(4.0));
        assertTotal(ana, 3.5);
        assertTotal(bruno, 4.0);
        mvc.perform(delete("/trashCollection/{id}", second)).andExpect(status().isNoContent());
        assertTotal(ana, 0.0);
        assertTotal(bruno, 4.0);
        mvc.perform(delete("/trashCollection/{id}", first)).andExpect(status().isNoContent());
        assertTotal(bruno, 0.0);
        mvc.perform(get("/trashCollection")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void totalsIgnoreLegacyValuesAndClientSuppliedTotalsAndFailedWrites() throws Exception {
        long ana = account("ana").getId();
        jdbc.update("update tb_user set total_collected_kg = 999 where id = ?", ana);
        assertTotal(ana, 0.0);
        long collection = create(ana, 2.0, 2.0);
        mvc.perform(put("/users/{id}", ana).contentType(MediaType.APPLICATION_JSON).content("""
            {"version":0,"name":"Ana Silva","email":"ana@example.com","totalCollectedKg":500}
            """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalCollectedKg").value(2.0));
        mvc.perform(put("/trashCollection/{id}", collection).contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(ana, -1.0))).andExpect(status().isBadRequest());
        mvc.perform(put("/trashCollection/{id}", collection).contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(999999L, 8.0))).andExpect(status().isNotFound());
        assertTotal(ana, 2.0);
        mvc.perform(get("/trashCollection/{id}", collection))
            .andExpect(jsonPath("$.weightKg").value(2.0))
            .andExpect(jsonPath("$.volunteer.totalCollectedKg").value(2.0));
    }

    @Test
    void unassignedCollectionsDoNotContributeAndCanBeAssignedOrUnassigned() throws Exception {
        long ana = account("ana").getId();
        var response = mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(null, 9.0))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.volunteer").isEmpty()).andReturn();
        long id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        assertTotal(ana, 0.0);
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(ana, 9.0))).andExpect(status().isOk())
            .andExpect(jsonPath("$.volunteer.totalCollectedKg").value(9.0));
        assertTotal(ana, 9.0);
        mvc.perform(put("/trashCollection/{id}", id).contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(null, 9.0, 1))).andExpect(status().isOk())
            .andExpect(jsonPath("$.volunteer").isEmpty());
        assertTotal(ana, 0.0);
    }

    @Test
    void volunteerOnlySeesOwnTotalsAndCannotTransferCollections() throws Exception {
        long ana = account("ana").getId();
        long bruno = account("bruno").getId();
        create(bruno, 7.0, 7.0);
        var response = mvc.perform(post("/trashCollection").with(user(Long.toString(ana)).roles("VOLUNTEER"))
            .contentType(MediaType.APPLICATION_JSON).content(collectionJson(null, 2.5)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.volunteer.totalCollectedKg").value(2.5)).andReturn();
        long id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/trashCollection").with(user(Long.toString(ana)).roles("VOLUNTEER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].volunteer.id").value(ana))
            .andExpect(jsonPath("$[0].volunteer.totalCollectedKg").value(2.5));
        mvc.perform(get("/users/{id}", ana).with(user(Long.toString(ana)).roles("VOLUNTEER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalCollectedKg").value(2.5));
        mvc.perform(get("/users/{id}", bruno).with(user(Long.toString(ana)).roles("VOLUNTEER")))
            .andExpect(status().isForbidden());
        mvc.perform(put("/trashCollection/{id}", id).with(user(Long.toString(ana)).roles("VOLUNTEER"))
            .contentType(MediaType.APPLICATION_JSON).content(collectionJson(bruno, 2.5)))
            .andExpect(status().isForbidden());
        assertTotal(ana, 2.5);
        assertTotal(bruno, 7.0);
    }

    private User account(String name) {
        return userService.insert(new User(null, name, name + "@example.com", "senha-inicial", 0.0));
    }

    private long create(long owner, double weight, double total) throws Exception {
        var response = mvc.perform(post("/trashCollection").contentType(MediaType.APPLICATION_JSON)
            .content(collectionJson(owner, weight))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.volunteer.totalCollectedKg").value(total))
            .andExpect(jsonPath("$..password").isEmpty()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    }

    private void assertTotal(long id, double total) throws Exception {
        mvc.perform(get("/users/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCollectedKg").value(total));
    }

    private String collectionJson(Long owner, double weight) {
        return collectionJson(owner, weight, 0);
    }

    private String collectionJson(Long owner, double weight, long version) {
        String volunteer = owner == null ? "null" : "{\"id\":" + owner + "}";
        return """
            {"version":%d,"beach":"Praia A","materialType":"Plastico","weightKg":%s,
             "registrationDate":"2026-10-15","volunteer":%s}
            """.formatted(version, weight, volunteer);
    }
}
