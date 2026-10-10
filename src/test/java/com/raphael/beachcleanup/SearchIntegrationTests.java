package com.raphael.beachcleanup;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.raphael.beachcleanup.entities.*;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.UserService;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:searchtests", "spring.jpa.show-sql=false",
    "spring.jpa.open-in-view=false"})
class SearchIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired CleanupEventRepository events;
    @Autowired TrashCollectionRepository collections;
    @Autowired UserService userService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        collections.deleteAll();
        users.deleteAll();
        events.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
            .defaultRequest(get("/").with(user("admin").roles("ADMIN"))).build();
    }

    @Test
    void publicEventSearchCombinesFiltersAndPaginatesInStableOrder() throws Exception {
        var first = event("Praia A", "Agendado", "2026-10-15T08:00:00");
        var second = event("Praia A", "Agendado", "2026-10-16T08:00:00");
        event("Praia B", "Cancelado", "2026-10-17T08:00:00");
        mvc.perform(get("/cleanupEvent/search").with(anonymous()).param("location", "praia a")
            .param("status", "agendado").param("from", "2026-10-15T08:00:00")
            .param("to", "2026-10-16T08:00:00").param("size", "1")
            .param("sortBy", "dateTime").param("direction", "desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.number").value(0))
            .andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.content[0].id").value(second.getId()));
        mvc.perform(get("/cleanupEvent/search").param("location", "Praia A").param("page", "1").param("size", "1"))
            .andExpect(jsonPath("$.content[0].id").value(second.getId()));
        mvc.perform(get("/cleanupEvent/search").param("page", "10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/cleanupEvent/search").param("location", "sem resultado"))
            .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
        mvc.perform(get("/cleanupEvent"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void collectionSearchFiltersAndKeepsFullUserTotalOutsideCurrentPage() throws Exception {
        User ana = account("Ana", "ana@example.com");
        User bruno = account("Bruno", "bruno@example.com");
        var event = event("Praia A", "Agendado", "2026-10-15T08:00:00");
        collection(ana, event, "Praia A", "Vidro", 2.0, "2026-10-15");
        var second = collection(ana, event, "Praia A", "Vidro", 3.5, "2026-10-16");
        collection(ana, null, "Praia B", "Plastico", 4.0, "2026-10-17");
        collection(bruno, event, "Praia A", "Vidro", 8.0, "2026-10-15");
        mvc.perform(get("/trashCollection/search").param("beach", "praia a").param("materialType", "vidro")
            .param("volunteerId", ana.getId().toString()).param("eventId", event.getId().toString())
            .param("from", "2026-10-15").param("to", "2026-10-16").param("page", "1").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].id").value(second.getId()))
            .andExpect(jsonPath("$.content[0].volunteer.totalCollectedKg").value(9.5))
            .andExpect(jsonPath("$..password").isEmpty());
        mvc.perform(get("/trashCollection/search").param("eventId", "999999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void collectionSearchAndPageCountsRemainIsolatedForVolunteers() throws Exception {
        User ana = account("Ana", "ana@example.com");
        User bruno = account("Bruno", "bruno@example.com");
        collection(ana, null, "Praia A", "Vidro", 2.0, "2026-10-15");
        collection(bruno, null, "Praia A", "Vidro", 8.0, "2026-10-15");
        mvc.perform(get("/trashCollection/search").with(user(ana.getId().toString()).roles("VOLUNTEER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].volunteer.id").value(ana.getId()))
            .andExpect(jsonPath("$.content[0].volunteer.totalCollectedKg").value(2.0));
        mvc.perform(get("/trashCollection/search").with(user(ana.getId().toString()).roles("VOLUNTEER"))
            .param("volunteerId", bruno.getId().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/users/search").with(user(ana.getId().toString()).roles("VOLUNTEER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/trashCollection/search").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/search").with(anonymous())).andExpect(status().isUnauthorized());
    }

    @Test
    void userSearchCombinesNameEmailAndTreatsWildcardsAsLiteralText() throws Exception {
        User ana = account("Ana_100%", "ana@example.com");
        account("AnaX100Y", "outra@example.com");
        mvc.perform(get("/users/search").param("name", "_100%").param("email", "ANA@"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(ana.getId()))
            .andExpect(jsonPath("$.content[0].totalCollectedKg").value(0.0))
            .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void invalidPaginationSortIdsAndDateRangesReturnBadRequest() throws Exception {
        for (String[] parameter : new String[][] {{"page", "-1"}, {"size", "0"}, {"size", "101"},
                {"page", "2147483647"}, {"sortBy", "password"}, {"direction", "invalido"},
                {"status", "invalido"}, {"from", "nao-e-data"}}) {
            mvc.perform(get("/cleanupEvent/search").param(parameter[0], parameter[1]))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(get("/cleanupEvent/search").param("from", "2026-10-20T08:00:00")
            .param("to", "2026-10-15T08:00:00")).andExpect(status().isBadRequest());
        mvc.perform(get("/trashCollection/search").param("from", "2026-10-20").param("to", "2026-10-15"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/trashCollection/search").param("volunteerId", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/users/search").param("name", "x".repeat(256))).andExpect(status().isBadRequest());
    }

    private User account(String name, String email) {
        return userService.insert(new User(null, name, email, "senha-segura", 0.0));
    }

    private CleanupEvent event(String location, String state, String date) {
        return events.save(new CleanupEvent(null, "Mutirao", location, LocalDateTime.parse(date), state));
    }

    private TrashCollection collection(User owner, CleanupEvent event, String beach, String material, double weight, String date) {
        var record = new TrashCollection(null, beach, material, weight, LocalDate.parse(date));
        record.setVolunteer(owner);
        record.setEvent(event);
        return collections.save(record);
    }
}
