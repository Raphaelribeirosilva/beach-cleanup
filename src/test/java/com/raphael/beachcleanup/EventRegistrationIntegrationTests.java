package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.entities.UserRole;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.services.EventRegistrationService;
import com.raphael.beachcleanup.services.UserService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:registrationtests",
    "spring.jpa.show-sql=false"
})
class EventRegistrationIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired CleanupEventRepository events;
    @Autowired TrashCollectionRepository collections;
    @Autowired UserService userService;
    @Autowired EventRegistrationService registrations;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        collections.deleteAll();
        users.deleteAll();
        events.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void registrationAndCancellationAreIdempotentAndListOnlyCurrentEvents() throws Exception {
        User ana = account("ana", false);
        String token = login(ana);
        long first = event().getId();
        long second = event().getId();
        mvc.perform(get("/users/me/events").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        for (long id : new long[] {second, first, first}) {
            mvc.perform(put("/users/me/events/{id}", id).header("Authorization", token))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        assertEquals(2, rowCount(ana.getId()));
        mvc.perform(get("/users/me/events").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(first)).andExpect(jsonPath("$[1].id").value(second))
            .andExpect(jsonPath("$[0].title").value("Mutirao"))
            .andExpect(jsonPath("$..password").isEmpty()).andExpect(jsonPath("$..email").isEmpty());
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(delete("/users/me/events/{id}", first).header("Authorization", token))
                .andExpect(status().isNoContent());
        }
        assertEquals(1, rowCount(ana.getId()));
        mvc.perform(get("/users/me/events").header("Authorization", token))
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(second));
        mvc.perform(delete("/users/me/events/{id}", second).header("Authorization", token))
            .andExpect(status().isNoContent());
        assertEquals(0, rowCount(ana.getId()));
    }

    @Test
    void userCannotModifyOrSeeSomeoneElsesRegistrationsEvenThroughBodyIds() throws Exception {
        User ana = account("ana", false);
        User bruno = account("bruno", false);
        String anaToken = login(ana);
        String brunoToken = login(bruno);
        long event = event().getId();
        mvc.perform(put("/users/me/events/{id}", event).header("Authorization", anaToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + bruno.getId() + "}"))
            .andExpect(status().isNoContent());
        assertEquals(1, rowCount(ana.getId()));
        assertEquals(0, rowCount(bruno.getId()));
        mvc.perform(get("/users/me/events").header("Authorization", brunoToken))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(delete("/users/me/events/{id}", event).header("Authorization", brunoToken))
            .andExpect(status().isNoContent());
        assertEquals(1, rowCount(ana.getId()));
        mvc.perform(get("/users/{id}/events", ana.getId()).header("Authorization", brunoToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void anonymousRequestsAreDeniedAndInvalidOrMissingEventsDoNotChangeRegistrations() throws Exception {
        long event = event().getId();
        mvc.perform(get("/users/me/events")).andExpect(status().isUnauthorized());
        mvc.perform(put("/users/me/events/{id}", event)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/users/me/events/{id}", event)).andExpect(status().isUnauthorized());
        String token = login(account("ana", false));
        mvc.perform(put("/users/me/events/999999").header("Authorization", token))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc.perform(delete("/users/me/events/999999").header("Authorization", token))
            .andExpect(status().isNotFound());
        for (String id : new String[] {"0", "-1", "invalido"}) {
            mvc.perform(put("/users/me/events/" + id).header("Authorization", token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
            mvc.perform(delete("/users/me/events/" + id).header("Authorization", token))
                .andExpect(status().isBadRequest());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from tb_event_volunteer", Integer.class));
    }

    @Test
    void adminCanManageOwnRegistrationAndCancellationPreservesCollectionsAndTotals() throws Exception {
        User admin = account("admin", true);
        String token = login(admin);
        long event = event().getId();
        // Collections do not require a registration in this stage.
        mvc.perform(post("/trashCollection").header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"beach":"Praia A","materialType":"Vidro","weightKg":2.5,
                 "registrationDate":"2026-10-15","volunteer":{"id":%d},"event":{"id":%d}}
                """.formatted(admin.getId(), event)))
            .andExpect(status().isCreated());
        mvc.perform(put("/users/me/events/{id}", event).header("Authorization", token))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/users/me/events/{id}", event).header("Authorization", token))
            .andExpect(status().isNoContent());
        assertEquals(1, collections.count());
        mvc.perform(get("/users/{id}", admin.getId()).header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalCollectedKg").value(2.5));
    }

    @Test
    void registrationSynchronizesBothSidesInTheSameTransaction() {
        long userId = account("ana", false).getId();
        long eventId = event().getId();
        transactions.executeWithoutResult(status -> {
            var user = users.findById(userId).orElseThrow();
            var event = events.findById(eventId).orElseThrow();
            registrations.register(userId, eventId);
            assertTrue(user.getEvents().contains(event));
            assertTrue(event.getUsers().contains(user));
            registrations.cancel(userId, eventId);
            assertFalse(user.getEvents().contains(event));
            assertFalse(event.getUsers().contains(user));
        });
        assertEquals(0, rowCount(userId));
    }

    @Test
    void concurrentRegistrationsDoNotDuplicateOrLoseDifferentEvents() throws Exception {
        User ana = account("ana", false);
        String token = login(ana);
        long first = event().getId();
        registerConcurrently(token, first, first);
        assertEquals(1, rowCount(ana.getId()));
        registerConcurrently(token, event().getId(), event().getId());
        assertEquals(3, rowCount(ana.getId()));
    }

    private void registerConcurrently(String token, long first, long second) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.LongStream.of(first, second).mapToObj(id -> executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(put("/users/me/events/{id}", id).header("Authorization", token))
                    .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            for (var task : tasks) {
                assertEquals(204, task.get(15, TimeUnit.SECONDS));
            }
        }
    }

    private int rowCount(long userId) {
        return jdbc.queryForObject("select count(*) from tb_event_volunteer where volunteer_id = ?",
            Integer.class, userId);
    }

    private User account(String name, boolean admin) {
        User user = userService.insert(new User(null, name, name + "@example.com", "senha-segura", 0.0));
        if (admin) {
            user.setRole(UserRole.ADMIN);
            user = users.save(user);
        }
        return user;
    }

    private String login(User user) throws Exception {
        var result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"senha-segura\"}"))
            .andExpect(status().isOk()).andReturn();
        return "Bearer " + mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private CleanupEvent event() {
        return events.save(new CleanupEvent(null, "Mutirao", "Praia A",
            LocalDateTime.parse("2026-10-15T08:00:00"), "Agendado"));
    }
}
