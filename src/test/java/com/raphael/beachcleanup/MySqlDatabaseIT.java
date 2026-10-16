package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.raphael.beachcleanup.dto.LoginDTO;
import com.raphael.beachcleanup.entities.*;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.services.*;

/** Opt-in tests. Every test creates its own schema on an explicitly configured disposable server. */
class MySqlDatabaseIT {
    private String serverUrl;
    private String username;
    private String password;
    private String schema;
    private String url;
    private String restoreSchema;

    @BeforeEach
    void createSchema() throws Exception {
        serverUrl = required("MYSQL_TEST_SERVER_URL");
        assertTrue(serverUrl.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/\\?.*"),
            "Use um servidor descartável em loopback, com porta explícita e sem banco na URL");
        username = required("MYSQL_TEST_USER");
        password = System.getenv("MYSQL_TEST_PASSWORD");
        assertNotNull(password, "Configure MYSQL_TEST_PASSWORD (pode ser vazio no servidor temporário local)");
        String generated = "beachcleanup_ci_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(serverUrl, username, password);
                var statement = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            statement.executeUpdate("CREATE DATABASE " + generated + " CHARACTER SET utf8mb4");
        }
        schema = generated; // Cleanup owns this schema only after successful creation.
        url = serverUrl.replace("/?", "/" + schema + "?");
    }

    @AfterEach
    void dropOwnedSchema() throws Exception {
        if (restoreSchema != null) {
            assertTrue(restoreSchema.matches("beachcleanup_ci_[a-f0-9]{32}"));
            try (var connection = DriverManager.getConnection(serverUrl, username, password);
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE " + restoreSchema);
            }
        }
        if (schema != null) {
            assertTrue(schema.matches("beachcleanup_ci_[a-f0-9]{32}"));
            try (var connection = DriverManager.getConnection(serverUrl, username, password);
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE " + schema);
            }
        }
    }

    @Test
    void backupRestoresAllDataAndStartsInSeparateDatabase() throws Exception {
        long userId;
        long eventId;
        try (var context = start()) {
            User owner = account(context, "Ana");
            userId = owner.getId();
            var event = context.getBean(CleanupEventService.class).insert(new CleanupEvent(null,
                "Mutirão de homologação", "Praia São José", LocalDateTime.parse("2026-10-16T08:00:00"), "Agendado"));
            eventId = event.getId();
            context.getBean(EventRegistrationService.class).register(userId, eventId);
            var collection = input(owner, 2.5);
            collection.setEvent(event);
            context.getBean(TrashCollectionService.class).insert(collection);
            var jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("update tb_user set role='ADMIN', credential_version=7, row_version=3 where id=?", userId);
            jdbc.update("update tb_trash_collection set row_version=4");
        }

        String generated = "beachcleanup_ci_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(serverUrl, username, password);
                var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + generated + " CHARACTER SET utf8mb4");
        }
        restoreSchema = generated;
        String restoredUrl = serverUrl.replace("/?", "/" + restoreSchema + "?");
        Path dump = Files.createTempFile("beachcleanup-backup-", ".sql");
        Path log = Files.createTempFile("beachcleanup-backup-", ".log");
        try {
            var dumpCommand = mysqlClient("mysqldump", "--single-transaction", "--quick", "--no-tablespaces",
                "--set-gtid-purged=OFF", "--routines", "--events", "--triggers",
                "--result-file=" + dump, schema).redirectOutput(log.toFile()).redirectErrorStream(true);
            runClient(dumpCommand);
            assertTrue(Files.size(dump) > 0);
            byte[] checksum = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump));
            runClient(mysqlClient("mysql", "--binary-mode=1", restoreSchema)
                .redirectInput(dump.toFile()).redirectOutput(log.toFile()).redirectErrorStream(true));
            assertArrayEquals(checksum, java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
            // Compare every column, including hashes, roles, versions, links and Flyway history.
            try (var source = DriverManager.getConnection(url, username, password);
                    var restored = DriverManager.getConnection(restoredUrl, username, password)) {
                for (String table : List.of("tb_user", "tb_cleanup_event", "tb_event_volunteer",
                        "tb_trash_collection", "flyway_schema_history")) {
                    assertEquals(tableRows(source, table), tableRows(restored, table), table);
                }
            }
            try (var context = start(restoredUrl)) {
                var owner = context.getBean(UserRepository.class).findById(userId).orElseThrow();
                assertEquals(userId, context.getBean(AuthService.class)
                    .login(new LoginDTO("ana@example.com", "senha-segura")).userId());
                assertEquals(UserRole.ADMIN, owner.getRole());
                assertEquals(7, owner.getCredentialVersion());
                assertEquals(2.5, context.getBean(CollectedTotalsService.class).user(owner).getTotalCollectedKg());
                assertEquals(eventId, context.getBean(EventRegistrationService.class).findMyEvents(userId).getFirst().id());
                assertEquals(6, context.getBean(Flyway.class).info().applied().length);
            }
        } finally {
            Files.deleteIfExists(dump);
            Files.deleteIfExists(log);
        }
    }

    private ProcessBuilder mysqlClient(String executable, String... arguments) {
        String directory = System.getenv("MYSQL_TEST_CLIENT_BIN");
        String command = directory == null || directory.isBlank() ? executable : Path.of(directory, executable).toString();
        var address = java.net.URI.create(serverUrl.substring("jdbc:".length()));
        var args = new java.util.ArrayList<>(List.of(command, "--no-defaults", "--protocol=tcp",
            "--host=" + address.getHost(), "--port=" + address.getPort(), "--user=" + username,
            "--default-character-set=utf8mb4"));
        args.addAll(List.of(arguments));
        var process = new ProcessBuilder(args);
        // Only disposable test credentials; never expose the password through argv or test logs.
        process.environment().put("MYSQL_PWD", password);
        return process;
    }

    private static void runClient(ProcessBuilder builder) throws Exception {
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "MySQL client timeout");
            assertEquals(0, process.exitValue(), "MySQL client failed (check local client installation/compatibility)");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static List<List<String>> tableRows(java.sql.Connection connection, String table) throws Exception {
        var rows = new java.util.ArrayList<List<String>>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT * FROM " + table)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                var row = new java.util.ArrayList<String>();
                for (int column = 1; column <= columns; column++) row.add(result.getString(column));
                rows.add(row);
            }
        }
        rows.sort(java.util.Comparator.comparing(Object::toString));
        return rows;
    }

    @Test
    void migrationsRestartAuthenticationAndConstraintsWorkOnMySql() throws Exception {
        long userId;
        long eventId;
        try (var context = start()) {
            var users = context.getBean(UserRepository.class);
            assertEquals(0, users.count());
            assertTrue(List.of(context.getEnvironment().getActiveProfiles()).containsAll(List.of("prod", "mysql")));
            String port = context.getEnvironment().getProperty("local.server.port");
            try (var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()) {
                for (String probe : new String[] {"health", "health/liveness", "health/readiness"}) {
                    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://127.0.0.1:" + port + "/actuator/" + probe))
                        .timeout(java.time.Duration.ofSeconds(5)).GET().build();
                    var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode());
                    assertTrue(response.body().contains("\"status\":\"UP\""));
                    assertFalse(response.body().contains("components"));
                }
            }
            User owner = account(context, "Ana");
            userId = owner.getId();
            var event = context.getBean(CleanupEventService.class).insert(new CleanupEvent(null,
                "Mutirão", "Praia A", LocalDateTime.parse("2026-10-15T08:00:00"), "Agendado"));
            eventId = event.getId();
            context.getBean(EventRegistrationService.class).register(userId, eventId);
            var collection = input(owner, 2.5);
            collection.setEvent(event);
            context.getBean(TrashCollectionService.class).insert(collection);
            assertEquals(6, context.getBean(Flyway.class).info().applied().length);
            assertFalse(context.getEnvironment().getProperty("spring.jpa.open-in-view", Boolean.class));
        }
        try (var context = start()) {
            User owner = context.getBean(UserRepository.class).findById(userId).orElseThrow();
            assertEquals(1, context.getBean(UserRepository.class).count());
            assertEquals(userId, context.getBean(AuthService.class)
                .login(new LoginDTO("ana@example.com", "senha-segura")).userId());
            assertEquals(2.5, context.getBean(CollectedTotalsService.class).user(owner).getTotalCollectedKg());
            assertEquals(eventId, context.getBean(EventRegistrationService.class).findMyEvents(userId).getFirst().id());
            var jdbc = context.getBean(JdbcTemplate.class);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into tb_user(name,email,password,role,credential_version)
                values ('Duplicada','ana@example.com','hash','VOLUNTEER',0)
                """));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into tb_event_volunteer(volunteer_id,event_id) values (?,?)", userId, eventId));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into tb_event_volunteer(volunteer_id,event_id) values (?,?)", userId, 999999));
        }
    }

    @Test
    void versionMigrationsPreserveExistingMySqlData() throws Exception {
        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration/mysql")
            .target("2").load().migrate();
        try (var connection = DriverManager.getConnection(url, username, password);
                var sql = connection.createStatement()) {
            sql.executeUpdate("""
                insert into tb_user(id,name,email,password,role,credential_version,row_version)
                values (101,'Ana','ana@example.com','hash-preservado','VOLUNTEER',7,3)
                """);
            sql.executeUpdate("""
                insert into tb_cleanup_event(id,title,location,date_time,status)
                values (202,'Mutirão','Praia A','2026-10-15 08:00:00','Agendado')
                """);
            sql.executeUpdate("insert into tb_event_volunteer(volunteer_id,event_id) values (101,202)");
            sql.executeUpdate("""
                insert into tb_trash_collection(id,beach,material_type,weight_kg,registration_date,volunteer_id,event_id)
                values (303,'Praia A','Vidro',2.5,'2026-10-15',101,202)
                """);
        }
        try (var context = start()) {
            assertEquals(6, context.getBean(Flyway.class).info().applied().length);
            var owner = context.getBean(UserRepository.class).findById(101L).orElseThrow();
            var collection = context.getBean(TrashCollectionRepository.class).findById(303L).orElseThrow();
            assertEquals("hash-preservado", owner.getPassword());
            assertEquals(7, owner.getCredentialVersion());
            assertEquals(101L, collection.getVolunteer().getId());
            assertEquals(202L, collection.getEvent().getId());
            assertEquals(2.5, collection.getWeightKg());
            assertEquals(LocalDate.parse("2026-10-15"), collection.getRegistrationDate());
            assertEquals(202L, context.getBean(EventRegistrationService.class).findMyEvents(101L).getFirst().id());
            var jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(0L, jdbc.queryForObject("select row_version from tb_trash_collection where id=303", Long.class));
            assertEquals(0L, jdbc.queryForObject("select row_version from tb_cleanup_event where id=202", Long.class));
        }
    }

    @Test
    void concurrentEditsCannotBothCommitOnMySql() throws Exception {
        try (var context = start()) {
            User owner = account(context, "Ana");
            long id = context.getBean(TrashCollectionService.class).insert(input(owner, 2.0)).getId();
            CountDownLatch loaded = new CountDownLatch(2);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> editSnapshot(context, loaded, id, owner, 3.0));
                var second = executor.submit(() -> editSnapshot(context, loaded, id, owner, 4.0));
                List<Boolean> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
            }
            var saved = context.getBean(TrashCollectionRepository.class).findById(id).orElseThrow();
            assertTrue(saved.getWeightKg() == 3.0 || saved.getWeightKg() == 4.0);
            assertEquals(saved.getWeightKg(), context.getBean(CollectedTotalsService.class).user(owner).getTotalCollectedKg());
        }
    }

    @Test
    void transferredCollectionRejectsStaleUpdateAndDeleteOnMySql() throws Exception {
        try (var context = start()) {
            User first = account(context, "Ana");
            User second = account(context, "Bruno");
            for (boolean delete : new boolean[] {false, true}) {
                long id = context.getBean(TrashCollectionService.class).insert(input(first, 2.0)).getId();
                CountDownLatch loaded = new CountDownLatch(1);
                CountDownLatch transferred = new CountDownLatch(1);
                try (var executor = Executors.newSingleThreadExecutor()) {
                    var stale = executor.submit(() -> {
                        authenticate(first.getId().toString(), "VOLUNTEER");
                        try {
                            assertThrows(OptimisticLockingFailureException.class, () -> transaction(context).execute(tx -> {
                                context.getBean(TrashCollectionRepository.class).findById(id).orElseThrow();
                                loaded.countDown();
                                await(transferred);
                                if (delete) context.getBean(TrashCollectionService.class).delete(id);
                                else context.getBean(TrashCollectionService.class).update(id, input(first, 9.0), 0L);
                                return null;
                            }));
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    });
                    try {
                        await(loaded);
                        authenticate("admin", "ADMIN");
                        context.getBean(TrashCollectionService.class).update(id, input(second, 4.0), 0L);
                    } finally {
                        SecurityContextHolder.clearContext();
                        transferred.countDown();
                    }
                    stale.get(30, TimeUnit.SECONDS);
                }
                var saved = context.getBean(TrashCollectionRepository.class).findById(id).orElseThrow();
                assertEquals(second.getId(), saved.getVolunteer().getId());
                assertEquals(4.0, saved.getWeightKg());
                authenticate(first.getId().toString(), "VOLUNTEER");
                try {
                    assertThrows(AccessDeniedException.class, () -> context.getBean(TrashCollectionService.class).delete(id));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        }
    }

    private boolean editSnapshot(ConfigurableApplicationContext context, CountDownLatch loaded,
            long id, User owner, double weight) {
        authenticate("admin", "ADMIN");
        try {
            transaction(context).execute(tx -> {
                context.getBean(TrashCollectionRepository.class).findById(id).orElseThrow();
                loaded.countDown();
                await(loaded);
                context.getBean(TrashCollectionService.class).update(id, input(owner, weight), 0L);
                return null;
            });
            return true;
        } catch (OptimisticLockingFailureException expected) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ConfigurableApplicationContext start() {
        return start(url);
    }

    private ConfigurableApplicationContext start(String databaseUrl) {
        var context = new SpringApplicationBuilder(BeachcleanupApplication.class).run(
            "--spring.profiles.active=prod", "--server.port=0", "--spring.datasource.url=" + databaseUrl,
            "--spring.datasource.username=" + username, "--spring.datasource.password=" + password,
            "--security.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "--logging.level.root=WARN");
        CatalogTestData.seed(context);
        return context;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "Configure " + name + " para executar -Pmysql-it");
        assertFalse(value.isBlank(), "Configure " + name + " para executar -Pmysql-it");
        return value;
    }

    private static TransactionTemplate transaction(ConfigurableApplicationContext context) {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    private static void authenticate(String name, String role) {
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new UsernamePasswordAuthenticationToken(name, "unused",
            List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(security);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "A operação concorrente não chegou ao ponto esperado");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static User account(ConfigurableApplicationContext context, String name) {
        return context.getBean(UserService.class).insert(
            new User(null, name, name.toLowerCase(java.util.Locale.ROOT) + "@example.com", "senha-segura", 0.0));
    }

    private static TrashCollection input(User owner, double weight) {
        var result = new TrashCollection(null, "Praia A", "Vidro", weight, LocalDate.parse("2026-10-15"));
        result.setVolunteer(owner);
        return result;
    }
}
