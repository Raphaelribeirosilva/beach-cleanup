package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.raphael.beachcleanup.dto.CleanupEventDTO;
import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.services.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared assertions against migrated H2 and a disposable real MySQL schema. */
final class DemoDataAssertions {
    static void lifecycle(String url, String user, String password, boolean mysql) throws Exception {
        List<CleanupEventDTO> snapshot;
        long originalId;
        try (var context = start(url, user, password, mysql, null)) {
            var jdbc = context.getBean(JdbcTemplate.class);
            assertTrue(context.getBeansOfType(DemoDataService.class).isEmpty());
            assertEquals(0, jdbc.queryForObject("select count(*) from tb_cleanup_event", Integer.class));
            CatalogTestData.seed(context);
            originalId = context.getBean(CleanupEventService.class).insert(new CleanupEvent(null,
                "Evento existente", "Praia A", LocalDate.now().atTime(9, 0), "Agendado")).getId();
        }
        try (var context = start(url, user, password, mysql, "true")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var events = context.getBean(CleanupEventService.class);
            var all = events.findAll();
            assertEquals(5, all.size());
            var demo = all.stream().filter(e -> !e.getId().equals(originalId)).toList();
            assertEquals(3, demo.stream().filter(e -> e.getStatus().equals("Agendado")).count());
            assertEquals(1, demo.stream().filter(e -> e.getStatus().equals("Concluido")).count());
            var today = LocalDate.now(context.getBean(Clock.class).withZone(ZoneId.of("America/Sao_Paulo")));
            assertEquals(List.of(today.plusDays(7), today.plusDays(14), today.plusDays(21), today.minusDays(7)),
                demo.stream().map(e -> e.getDateTime().toLocalDate()).toList());
            assertEquals(List.of("Guarapari", "Guarapari", "Vila Velha", "Vila Velha"),
                demo.stream().map(e -> e.getBeachReference().getCity()).toList());
            for (var event : demo) {
                assertTrue(event.getTitle().startsWith("[DEMONSTRAÇÃO FICTÍCIA]"));
                assertTrue(event.getDescription().contains("Não há ação real agendada"));
                assertEquals(event.getDescription(), new CleanupEventDTO(event).description());
                assertTrue(event.getTitle().length() <= 255);
                assertEquals("ES", event.getBeachReference().getState());
            }
            var history = jdbc.queryForList("select previous_state,next_state,actor_role,actor_id from tb_audit_entry order by id");
            assertEquals(2, history.size());
            assertEquals("Agendado", history.get(0).get("previous_state"));
            assertEquals("Em andamento", history.get(0).get("next_state"));
            assertEquals("Em andamento", history.get(1).get("previous_state"));
            assertEquals("Concluido", history.get(1).get("next_state"));
            history.forEach(entry -> { assertEquals("SYSTEM", entry.get("actor_role")); assertNull(entry.get("actor_id")); });
            // A later manual edit must survive enabled restarts, including the description.
            var first = demo.getFirst();
            events.update(first.getId(), new CleanupEvent(null, "[DEMONSTRAÇÃO FICTÍCIA] Título revisado",
                first.getLocation(), first.getDateTime().plusDays(1), first.getStatus()), first.getRowVersion());
            snapshot = events.findAll().stream().map(CleanupEventDTO::new).toList();
            assertEquals("Evento existente", events.findById(originalId).getTitle());
            assertEquals(0, jdbc.queryForObject("select count(*) from tb_user", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from tb_trash_collection", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from tb_account_mail_queue", Integer.class));
        }
        for (String enabled : List.of("true", "false")) {
            try (var context = start(url, user, password, mysql, enabled)) {
                assertEquals(snapshot, context.getBean(CleanupEventService.class).findAll().stream().map(CleanupEventDTO::new).toList());
                assertEquals(2, context.getBean(JdbcTemplate.class).queryForObject("select count(*) from tb_audit_entry", Integer.class));
            }
        }
    }

    static void atomicAndConcurrent(String url, String user, String password, boolean mysql) throws Exception {
        try (var context = start(url, user, password, mysql, "false")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var service = new DemoDataService(jdbc, context.getBean(CleanupEventService.class),
                context.getBean(CatalogService.class), context.getBean(Clock.class));
            long beaches = jdbc.queryForObject("select count(*) from tb_catalog_entry", Long.class);
            transaction.executeWithoutResult(status -> { service.seed(); status.setRollbackOnly(); });
            assertEquals(0, context.getBean(CleanupEventRepository.class).count());
            assertEquals(beaches, jdbc.queryForObject("select count(*) from tb_catalog_entry", Long.class));
            assertFalse(jdbc.queryForObject("select completed from tb_demo_data", Boolean.class));
            var ready = new CountDownLatch(2);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> {
                    ready.countDown();
                    try { assertTrue(ready.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                    transaction.executeWithoutResult(status -> service.seed());
                }));
                for (var future : futures) future.get(30, TimeUnit.SECONDS);
            }
            assertEquals(4, context.getBean(CleanupEventRepository.class).count());
            assertTrue(jdbc.queryForObject("select completed from tb_demo_data", Boolean.class));
            // Deleting a demo event deliberately must not recreate it on the next run.
            var first = context.getBean(CleanupEventService.class).findAll().getFirst();
            context.getBean(CleanupEventService.class).delete(first.getId(), first.getRowVersion());
            transaction.executeWithoutResult(status -> service.seed());
            assertEquals(3, context.getBean(CleanupEventRepository.class).count());
        }
    }

    private static ConfigurableApplicationContext start(String url, String user, String password, boolean mysql, String enabled) {
        var args = new ArrayList<>(List.of("--spring.profiles.active=mysql", "--server.port=0",
            "--spring.datasource.url=" + url, "--spring.datasource.username=" + user, "--spring.datasource.password=" + password,
            "--spring.datasource.driver-class-name=" + (mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver"),
            "--spring.jpa.database-platform=" + (mysql ? "org.hibernate.dialect.MySQLDialect" : "org.hibernate.dialect.H2Dialect"),
            "--security.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "--logging.level.root=WARN"));
        if (enabled != null) args.add("--demo.data.enabled=" + enabled);
        return new SpringApplicationBuilder(BeachcleanupApplication.class).run(args.toArray(String[]::new));
    }
}
