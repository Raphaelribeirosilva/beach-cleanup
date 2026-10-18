package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.TrashCollectionService;
import com.raphael.beachcleanup.services.UserService;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:collectionconcurrencytests;LOCK_TIMEOUT=10000",
    "spring.jpa.show-sql=false"
})
class CollectionConcurrencyIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired TrashCollectionRepository collections;
    @Autowired UserRepository users;
    @Autowired CleanupEventRepository events;
    @Autowired UserService userService;
    @Autowired TrashCollectionService service;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;
    User first;
    User second;
    long collectionId;

    @BeforeEach
    void setUp() {
        CatalogTestData.seed(context);
        collections.deleteAll();
        users.deleteAll();
        events.deleteAll();
        first = userService.insert(new User(null, "Ana", "ana@example.com", "senha-segura-forte", 0.0));
        second = userService.insert(new User(null, "Bruno", "bruno@example.com", "senha-segura-forte", 0.0));
        TrashCollection collection = input(first, 2.0);
        collectionId = collections.saveAndFlush(collection).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void simultaneousHttpEditsOfTheSameSnapshotReturnOneConflict() throws Exception {
        CountDownLatch loaded = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> editSnapshot(loaded, 3.0));
            var b = executor.submit(() -> editSnapshot(loaded, 4.0));
            List<Integer> statuses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(code -> code == 200).count());
            assertEquals(1, statuses.stream().filter(code -> code == 409).count());
        }
        double weight = collections.findById(collectionId).orElseThrow().getWeightKg().doubleValue();
        assertTrue(weight == 3.0 || weight == 4.0);
        mvc.perform(get("/users/{id}", first.getId()).with(user("admin").roles("ADMIN")))
            .andExpect(jsonPath("$.totalCollectedKg").value(weight));
        mvc.perform(get("/trashCollection/{id}", collectionId).with(user("admin").roles("ADMIN")))
            .andExpect(jsonPath("$..rowVersion").isEmpty());
    }

    @Test
    void staleOwnerCannotUndoAnAdministratorTransfer() throws Exception {
        transferWhileOldOwnerWaits(false);
    }

    @Test
    void staleOwnerCannotDeleteAnAdministratorTransferredCollection() throws Exception {
        transferWhileOldOwnerWaits(true);
    }

    @Test
    void serviceRejectsFormerOwnerWithoutRelyingOnControllerChecks() throws Exception {
        transfer();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new UsernamePasswordAuthenticationToken(first.getId().toString(), "unused",
            List.of(new SimpleGrantedAuthority("ROLE_VOLUNTEER"))));
        SecurityContextHolder.setContext(security);
        try {
            assertThrows(AccessDeniedException.class, () -> service.update(collectionId, input(first, 9.0), 0L));
            assertThrows(AccessDeniedException.class, () -> service.delete(collectionId));
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertTransferred();
        mvc.perform(put("/trashCollection/{id}", collectionId)
            .with(user(second.getId().toString()).roles("VOLUNTEER"))
            .contentType(MediaType.APPLICATION_JSON).content(json(second, 5.0, 1)))
            .andExpect(status().isOk());
        mvc.perform(delete("/trashCollection/{id}", collectionId).param("version", collections.findById(collectionId).map(v -> v.getRowVersion().toString()).orElse("0"))
            .with(user(second.getId().toString()).roles("VOLUNTEER")))
            .andExpect(status().isNoContent());
    }

    private int editSnapshot(CountDownLatch loaded, double weight) {
        return new TransactionTemplate(transactions).execute(tx -> {
            // Models the entity already loaded by the controller's ownership check / OSIV.
            collections.findById(collectionId).orElseThrow();
            loaded.countDown();
            await(loaded);
            try {
                var response = mvc.perform(put("/trashCollection/{id}", collectionId)
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(json(first, weight))).andReturn().getResponse();
                if (response.getStatus() == 409) {
                    tx.setRollbackOnly();
                    assertTrue(response.getContentAsString().contains("Concurrent update"));
                }
                return response.getStatus();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    private void transferWhileOldOwnerWaits(boolean delete) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch transferred = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                collections.findById(collectionId).orElseThrow();
                loaded.countDown();
                await(transferred);
                try {
                    var request = delete ? delete("/trashCollection/{id}", collectionId).param("version", collections.findById(collectionId).map(v -> v.getRowVersion().toString()).orElse("0"))
                        : put("/trashCollection/{id}", collectionId).contentType(MediaType.APPLICATION_JSON)
                            .content(json(first, 9.0));
                    var response = mvc.perform(request.with(user(first.getId().toString()).roles("VOLUNTEER")))
                        .andReturn().getResponse();
                    tx.setRollbackOnly();
                    assertEquals(409, response.getStatus());
                    assertTrue(response.getContentAsString().contains("Concurrent update"));
                    return response.getStatus();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }));
            try {
                await(loaded);
                transfer();
            } finally {
                transferred.countDown();
            }
            assertEquals(409, stale.get(30, TimeUnit.SECONDS));
        }
        assertTransferred();
        mvc.perform(get("/users/{id}", first.getId()).with(user("admin").roles("ADMIN")))
            .andExpect(jsonPath("$.totalCollectedKg").value(0.0));
        mvc.perform(get("/users/{id}", second.getId()).with(user("admin").roles("ADMIN")))
            .andExpect(jsonPath("$.totalCollectedKg").value(4.0));
        mvc.perform(delete("/trashCollection/{id}", collectionId).param("version", collections.findById(collectionId).map(v -> v.getRowVersion().toString()).orElse("0"))
            .with(user(first.getId().toString()).roles("VOLUNTEER"))).andExpect(status().isForbidden());
    }

    private void transfer() throws Exception {
        mvc.perform(put("/trashCollection/{id}", collectionId).with(user("admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(json(second, 4.0))).andExpect(status().isOk());
    }

    private void assertTransferred() {
        var saved = collections.findById(collectionId).orElseThrow();
        assertEquals(second.getId(), saved.getVolunteer().getId());
        assertEquals(4.0, saved.getWeightKg().doubleValue());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "A operação concorrente não chegou ao ponto esperado");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private TrashCollection input(User owner, double weight) {
        var result = new TrashCollection(null, "Praia A", "Vidro", weight, LocalDate.parse("2026-10-15"));
        result.setVolunteer(owner);
        return result;
    }

    private String json(User owner, double weight) {
        return json(owner, weight, 0);
    }

    private String json(User owner, double weight, long version) {
        return """
            {"version":%d,"beach":"Praia A","materialType":"Vidro","weightKg":%s,
             "registrationDate":"2026-10-15","volunteer":{"id":%d}}
            """.formatted(version, weight, owner.getId());
    }
}
