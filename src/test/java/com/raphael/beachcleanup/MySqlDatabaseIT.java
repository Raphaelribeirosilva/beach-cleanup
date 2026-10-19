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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void representativePortalVolumeHasBoundedQueryCountsOnMySql() throws Exception {
        try (var context = start()) {
            PortalPerformanceProbe.run(context,"mysql", "MySQL " + context.getBean(JdbcTemplate.class).queryForObject("select version()",String.class) + " loopback");
        }
    }

    @Test
    void backupRestoresAllDataAndStartsInSeparateDatabase() throws Exception {
        long userId;
        long eventId;
        int comparedTables;
        long began = System.nanoTime();
        com.raphael.beachcleanup.dto.TokenDTO oldSession;
        String oldAction = "r".repeat(43);
        try (var context = start()) {
            User owner = account(context, "Ana");
            userId = owner.getId();
            var event = context.getBean(CleanupEventService.class).insert(new CleanupEvent(null,
                "Mutirão de homologação", "Praia São José", LocalDateTime.parse("2026-10-16T08:00:00"), "Agendado"));
            eventId = event.getId();
            context.getBean(EventRegistrationService.class).register(userId, eventId);
            var collection = input(owner, 2.5);
            collection.setBeach(event.getLocation());
            collection.setRegistrationDate(event.getDateTime().toLocalDate());
            collection.setEvent(event);
            context.getBean(TrashCollectionService.class).insert(collection);
            var jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("update tb_user set role='ADMIN', credential_version=7, row_version=3 where id=?", userId);
            jdbc.update("update tb_trash_collection set row_version=4");
            oldSession = context.getBean(AuthService.class).login(new LoginDTO("ana@example.com", "senha-segura-forte"));
            String actionHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(oldAction.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            context.getBean(AccountActionTokenRepository.class).saveAndFlush(new AccountActionToken(actionHash,
                context.getBean(UserRepository.class).findById(userId).orElseThrow(),AccountActionService.VERIFY,java.time.Instant.now().plusSeconds(3600)));
            jdbc.update("insert into tb_auth_rate_limit(bucket_key,window_start,attempts) values (?,UTC_TIMESTAMP(6),1)", "b".repeat(64));

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
                var sourceTables = databaseTables(source);
                comparedTables = sourceTables.size();
                assertEquals(sourceTables,databaseTables(restored));
                assertTrue(sourceTables.containsAll(List.of("tb_auth_session","tb_refresh_token","tb_account_action_token","tb_auth_rate_limit","tb_audit_entry")));
                for (String table : sourceTables) assertEquals(tableRows(source, table), tableRows(restored, table), table);
                // Compare exact restored state first; then quarantine credentials before serving traffic.
                try (var sql = restored.createStatement()) {
                    restored.setAutoCommit(false);
                    sql.executeUpdate("update tb_auth_session set revoked_at=UTC_TIMESTAMP(6) where revoked_at is null");
                    sql.executeUpdate("update tb_account_action_token set consumed=1");
                    sql.executeUpdate("update tb_user set credential_version=credential_version+1");
                    restored.commit();
                }
            }
            try (var context = start(restoredUrl)) {
                var owner = context.getBean(UserRepository.class).findById(userId).orElseThrow();
                assertEquals(userId, context.getBean(AuthService.class)
                    .login(new LoginDTO("ana@example.com", "senha-segura-forte")).userId());
                assertEquals(UserRole.ADMIN, owner.getRole());
                assertEquals(8, owner.getCredentialVersion());
                assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(oldSession.accessToken()));
                assertThrows(org.springframework.security.authentication.BadCredentialsException.class, () -> context.getBean(AuthService.class).refresh(oldSession.refreshToken()));
                assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class, () -> context.getBean(AccountActionService.class).verify(oldAction));
                assertEquals(2.5, context.getBean(CollectedTotalsService.class).user(owner).getTotalCollectedKg().doubleValue());
                assertEquals(eventId, context.getBean(EventRegistrationService.class).findMyEvents(userId).getFirst().id());
                assertEquals(10, context.getBean(Flyway.class).info().applied().length);
                var proof = java.util.Map.of("scope","disposable MySQL local restore", "status","passed",
                    "allTablesCompared",comparedTables,"sessionsQuarantined",true,"oldAccessAndRefreshRejected",true,
                    "oldAccountLinksRejected",true,"applicationLoginAndTotals","passed",
                    "elapsedSeconds",(System.nanoTime()-began)/1e9,"hostedAcceptance","pending");
                Files.writeString(Path.of("target/staging-recovery-proof.json"),new tools.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(proof));
            }
        } finally {
            Files.deleteIfExists(dump);
            Files.deleteIfExists(log);
        }
    }

    private static List<String> databaseTables(java.sql.Connection connection) throws Exception {
        var names = new java.util.ArrayList<String>();
        try (var sql=connection.createStatement(); var rows=sql.executeQuery("select table_name from information_schema.tables where table_schema=database() and table_type='BASE TABLE' order by table_name")) {
            while(rows.next()) { String name=rows.getString(1); assertTrue(name.matches("[A-Za-z0-9_]+")); names.add(name); }
        }
        return names;
    }

    @Test
    void runtimeAccountCanOperateWithoutSchemaOrGlobalPrivileges() throws Exception {
        try (var migrated=start()) { assertEquals(10,migrated.getBean(Flyway.class).info().applied().length); }
        String account="beach_app_"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        try (var root=DriverManager.getConnection(serverUrl,username,password); var sql=root.createStatement()) {
            sql.executeUpdate("create user '"+account+"'@'localhost' identified by 'discardable-runtime-test-password'");
            try {
                sql.executeUpdate("grant select,insert,update,delete on `"+schema+"`.* to '"+account+"'@'localhost'");
                try (var runtime=new SpringApplicationBuilder(BeachcleanupApplication.class).run(
                    "--spring.profiles.active=prod","--spring.flyway.enabled=false","--security.database.external-migrations=true","--server.port=0",
                    "--spring.datasource.url="+url,"--spring.datasource.username="+account,"--spring.datasource.password=discardable-runtime-test-password",
                    "--security.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","--security.accounts.require-verified-email=false",
                    "--security.abuse.enabled=false","--logging.level.root=WARN")) {
                    var owner=account(runtime,"Runtime");
                    runtime.getBean(TrashCollectionService.class).insert(input(owner,2.5));
                    assertEquals(2.5,runtime.getBean(CollectedTotalsService.class).user(owner).getTotalCollectedKg().doubleValue());
                    assertNotNull(runtime.getBean(AuthService.class).login(new LoginDTO("runtime@example.com","senha-segura-forte")).refreshToken());
                    var jdbc=runtime.getBean(JdbcTemplate.class);
                    assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute("create table denied_schema_change(id bigint)"));
                    assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.queryForList("select * from mysql.user"));
                }
                sql.executeUpdate("delete from `"+schema+"`.flyway_schema_history where version='10'");
                var failure = assertThrows(Exception.class, () -> new SpringApplicationBuilder(BeachcleanupApplication.class).run(
                    "--spring.profiles.active=prod","--spring.flyway.enabled=false","--security.database.external-migrations=true","--server.port=0",
                    "--spring.datasource.url="+url,"--spring.datasource.username="+account,"--spring.datasource.password=discardable-runtime-test-password",
                    "--security.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","--logging.level.root=OFF"));
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                assertTrue(cause instanceof org.flywaydb.core.api.exception.FlywayValidateException
                    || cause.getMessage().contains("Execute as migrações"), cause.toString());
            } finally { sql.executeUpdate("drop user '"+account+"'@'localhost'"); }
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
        return tableRows(connection, table, "*");
    }

    private static List<List<String>> tableRows(java.sql.Connection connection, String table, String columns) throws Exception {
        var rows = new java.util.ArrayList<List<String>>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT " + columns + " FROM " + table)) {
            int columnCount = result.getMetaData().getColumnCount();
            while (result.next()) {
                var row = new java.util.ArrayList<String>();
                for (int column = 1; column <= columnCount; column++) {
                    var value = result.getObject(column);
                    row.add(value instanceof Number ? new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString() : result.getString(column));
                }
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
            collection.setBeach(event.getLocation());
            collection.setRegistrationDate(event.getDateTime().toLocalDate());
            collection.setEvent(event);
            context.getBean(TrashCollectionService.class).insert(collection);
            assertEquals(10, context.getBean(Flyway.class).info().applied().length);
            assertFalse(context.getEnvironment().getProperty("spring.jpa.open-in-view", Boolean.class));
        }
        try (var context = start()) {
            User owner = context.getBean(UserRepository.class).findById(userId).orElseThrow();
            assertEquals(1, context.getBean(UserRepository.class).count());
            assertEquals(userId, context.getBean(AuthService.class)
                .login(new LoginDTO("ana@example.com", "senha-segura-forte")).userId());
            assertEquals(2.5, context.getBean(CollectedTotalsService.class).user(owner).getTotalCollectedKg().doubleValue());
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
            assertEquals(10, context.getBean(Flyway.class).info().applied().length);
            var owner = context.getBean(UserRepository.class).findById(101L).orElseThrow();
            var collection = context.getBean(TrashCollectionRepository.class).findById(303L).orElseThrow();
            assertEquals("hash-preservado", owner.getPassword());
            assertEquals(7, owner.getCredentialVersion());
            assertEquals(101L, collection.getVolunteer().getId());
            assertEquals(202L, collection.getEvent().getId());
            assertEquals(2.5, collection.getWeightKg().doubleValue());
            assertEquals(LocalDate.parse("2026-10-15"), collection.getRegistrationDate());
            assertEquals(202L, context.getBean(EventRegistrationService.class).findMyEvents(101L).getFirst().id());
            var jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(0L, jdbc.queryForObject("select row_version from tb_trash_collection where id=303", Long.class));
            assertEquals(0L, jdbc.queryForObject("select row_version from tb_cleanup_event where id=202", Long.class));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void catalogUpgradePreservesEveryLegacyColumnAndCredentialsOnMySql(int previousVersion) throws Exception {
        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration/mysql")
            .target(Integer.toString(previousVersion)).load().migrate();
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("senha-legada");
        var columns = new java.util.LinkedHashMap<String, String>();
        var snapshots = new java.util.LinkedHashMap<String, List<List<String>>>();
        try (var connection = DriverManager.getConnection(url, username, password);
                var sql = connection.createStatement()) {
            try (var insert = connection.prepareStatement("""
                    insert into tb_user(id,name,email,password,role,credential_version,row_version,total_collected_kg)
                    values (?, ?, ?, ?, ?, 3, 7, 99)
                    """)) {
                for (int id : new int[] {101, 102}) {
                    insert.setInt(1, id);
                    insert.setString(2, "Conta " + id);
                    insert.setString(3, "conta" + id + "@example.com");
                    insert.setString(4, hash);
                    insert.setString(5, id == 101 ? "ADMIN" : "VOLUNTEER");
                    insert.executeUpdate();
                }
            }
            sql.executeUpdate("""
                insert into tb_cleanup_event(id,title,location,date_time,status) values
                (501,'Mutirão A','Praia do Morro','2026-10-15 08:00:00','Agendado'),
                (502,'Mutirão B','  PRAIA   DO MORRO ','2026-10-16 08:00:00','Concluido'),
                (503,'Mutirão C','Morro','2026-10-17 08:00:00','Pausado')
                """);
            sql.executeUpdate("insert into tb_event_volunteer(volunteer_id,event_id) values (101,501),(102,501),(102,502)");
            sql.executeUpdate("""
                insert into tb_trash_collection(id,beach,material_type,weight_kg,registration_date,volunteer_id,event_id) values
                (601,'Praia do Morro','Plástico',2.25,'2026-10-15',101,501),
                (602,'praia do morro',' PLASTICO ',3.5,'2026-10-16',102,502),
                (603,'Morro','Vidro',4.75,'2026-10-17',102,null),
                (604,null,' ',0.5,'2026-10-18',101,null),
                (605,'','Vidro',1.25,'2026-10-19',null,null)
                """);
            if (previousVersion == 4) {
                sql.executeUpdate("update tb_cleanup_event set row_version=id-500");
                sql.executeUpdate("update tb_trash_collection set row_version=id-600");
            }
            // Save every original column, including legacy labels and stored totals, before upgrading.
            for (String table : List.of("tb_user", "tb_cleanup_event", "tb_event_volunteer", "tb_trash_collection")) {
                try (var rows = sql.executeQuery("select * from " + table + " where 1=0")) {
                    var names = new java.util.ArrayList<String>();
                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) names.add(rows.getMetaData().getColumnName(i));
                    columns.put(table, String.join(",", names));
                }
                snapshots.put(table, tableRows(connection, table));
            }
        }
        try (var context = start(url, false)) {
            var flyway = context.getBean(Flyway.class);
            flyway.validate();
            assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), java.util.Arrays.stream(flyway.info().applied())
                .map(migration -> migration.getVersion().toString()).toList());
            assertEquals(0, flyway.migrate().migrationsExecuted, "Restart/second migration must be a no-op");
            var jdbc = context.getBean(JdbcTemplate.class);
            try (var connection = DriverManager.getConnection(url, username, password)) {
                for (String table : columns.keySet()) {
                    assertEquals(snapshots.get(table), tableRows(connection, table, columns.get(table)), table);
                }
            }
            for (long id : new long[] {101, 102}) {
                assertEquals(id, context.getBean(AuthService.class)
                    .login(new LoginDTO("conta" + id + "@example.com", "senha-legada")).userId());
            }
            assertEquals(2, jdbc.queryForObject("select count(*) from tb_catalog_entry where kind='beaches'", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(*) from tb_catalog_entry where kind='materials'", Integer.class));
            Long beach = jdbc.queryForObject("select beach_id from tb_cleanup_event where id=501", Long.class);
            assertNotNull(beach);
            assertEquals(beach, jdbc.queryForObject("select beach_id from tb_cleanup_event where id=502", Long.class));
            assertEquals(beach, jdbc.queryForObject("select beach_id from tb_trash_collection where id=602", Long.class));
            assertNotEquals(beach, jdbc.queryForObject("select beach_id from tb_cleanup_event where id=503", Long.class));
            assertEquals(jdbc.queryForObject("select material_id from tb_trash_collection where id=601", Long.class),
                jdbc.queryForObject("select material_id from tb_trash_collection where id=602", Long.class));
            assertNull(jdbc.queryForObject("select beach_id from tb_trash_collection where id=604", Long.class));
            assertNull(jdbc.queryForObject("select material_id from tb_trash_collection where id=604", Long.class));
            assertNull(jdbc.queryForObject("select beach_id from tb_trash_collection where id=605", Long.class));
            assertEquals(12.25, jdbc.queryForObject("select sum(weight_kg) from tb_trash_collection", Double.class));
            var totals = context.getBean(CollectedTotalsService.class);
            var users = context.getBean(UserRepository.class);
            assertEquals(2.75, totals.user(users.findById(101L).orElseThrow()).getTotalCollectedKg().doubleValue());
            assertEquals(8.25, totals.user(users.findById(102L).orElseThrow()).getTotalCollectedKg().doubleValue());
            assertEquals(4, jdbc.queryForObject("select count(*) from tb_catalog_alias", Integer.class));
        }
        try (var context = start(url, false)) {
            assertEquals(2, context.getBean(UserRepository.class).count());
            assertEquals(5, context.getBean(TrashCollectionRepository.class).count());
            assertEquals(4, context.getBean(JdbcTemplate.class).queryForObject("select count(*) from tb_catalog_entry", Integer.class));
            assertEquals(102L, context.getBean(AuthService.class)
                .login(new LoginDTO("conta102@example.com", "senha-legada")).userId());
        }
    }

    @Test
    void decimalUpgradeIsAuditedAndLegacyIssuesAreReportedOnRealMySql() throws Exception {
        WeightMigrationAssertions.verify(url, username, password);
        try (var context = start(url, false)) {
            assertEquals(10, context.getBean(Flyway.class).info().applied().length);
            assertEquals(101L, context.getBean(AuthService.class)
                .login(new LoginDTO("legacy@example.com", "senha-antiga")).userId());
            var report = context.getBean(DataQualityService.class);
            assertEquals(7, report.rounding(0, 20).totalElements());
            assertTrue(report.issues(0, 100).content().stream().anyMatch(issue -> issue.code().equals("INVALID_WEIGHT")));
            assertTrue(report.issues(0, 100).content().stream().anyMatch(issue -> issue.code().equals("MISSING_BEACH")));
            assertTrue(report.issues(0, 100).content().stream().anyMatch(issue -> issue.code().equals("EVENT_DATE")));
        }
    }

    @Test
    void eventEditCannotMissACollectionCommittedAfterAnEarlierSnapshot() throws Exception {
        try (var context = start()) {
            User owner = account(context, "Ana");
            var event = context.getBean(CleanupEventService.class).insert(new CleanupEvent(null,
                "Mutirão", "Praia A", LocalDateTime.parse("2026-10-15T08:00:00"), "Agendado"));
            var snapshot = new CountDownLatch(1);
            var inserted = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var edit = executor.submit(() -> {
                    assertThrows(com.raphael.beachcleanup.exceptions.EventStateConflictException.class,
                        () -> transaction(context).execute(tx -> {
                            context.getBean(JdbcTemplate.class).queryForObject("select count(*) from tb_trash_collection", Long.class);
                            snapshot.countDown();
                            await(inserted);
                            return context.getBean(CleanupEventService.class).update(event.getId(), new CleanupEvent(null,
                                "Movido", "Praia B", event.getDateTime(), "Agendado"), 0L);
                        }));
                });
                try {
                    await(snapshot);
                    var collection = input(owner, 2.0);
                    collection.setEvent(event);
                    context.getBean(TrashCollectionService.class).insert(collection);
                } finally { inserted.countDown(); }
                edit.get(30, TimeUnit.SECONDS);
            }
            assertEquals("Praia A", context.getBean(CleanupEventRepository.class).findById(event.getId()).orElseThrow().getLocation());
            assertEquals(1, context.getBean(TrashCollectionRepository.class).count());
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
            assertTrue(saved.getWeightKg().doubleValue() == 3.0 || saved.getWeightKg().doubleValue() == 4.0);
            assertEquals(saved.getWeightKg().doubleValue(), context.getBean(CollectedTotalsService.class).user(owner).getTotalCollectedKg().doubleValue());
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
                                if (delete) context.getBean(TrashCollectionService.class).delete(id, 0L);
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
                assertEquals(4.0, saved.getWeightKg().doubleValue());
                authenticate(first.getId().toString(), "VOLUNTEER");
                try {
                    assertThrows(AccessDeniedException.class, () -> context.getBean(TrashCollectionService.class).delete(id));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        }
    }

    @Test
    void impactReportsReconcileDecimalsCatalogIdsAndRegistrationsOnMySql() throws Exception {
        try (var context = start()) {
            var owner = account(context, "Ana");
            var registered = account(context, "Inscrito");
            var event = context.getBean(CleanupEventService.class).insert(new CleanupEvent(null,
                "Impacto", "Praia A", LocalDateTime.parse("2026-10-15T09:00:00"), "Agendado"));
            var writer = context.getBean(TrashCollectionService.class);
            var linked = input(owner, 1.125); linked.setEvent(event);
            var first = writer.insert(linked);
            writer.insert(input(owner, 2.250));
            writer.insert(input(null, 0.001));
            context.getBean(JdbcTemplate.class).update(
                "insert into tb_event_volunteer(event_id, volunteer_id) values (?, ?)", event.getId(), registered.getId());
            var report = context.getBean(ImpactReportService.class);
            var day = LocalDate.parse("2026-10-15");
            for (var group : List.of("beach", "material", "period", "event")) {
                var result = report.impact(day, day, null, null, null, group);
                assertEquals(0, result.totals().weightKg().compareTo(new java.math.BigDecimal("3.376")));
                assertEquals(3, result.totals().collections()); assertEquals(1, result.totals().collectors());
                assertEquals(0, result.rows().stream().map(ImpactReportService.Row::weightKg)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add).compareTo(result.totals().weightKg()));
            }
            var catalog = context.getBean(CatalogService.class);
            Long beachId = catalog.list("beaches").stream().filter(item -> item.name().equals("Praia A")).findFirst().orElseThrow().id();
            Long materialId = catalog.list("materials").stream().filter(item -> item.name().equals("Vidro")).findFirst().orElseThrow().id();
            var selected = report.impact(day, day, beachId, materialId, event.getId(), "event");
            assertEquals(0, selected.totals().weightKg().compareTo(new java.math.BigDecimal("1.125")));
            assertEquals(1, report.participation(day, day, beachId, event.getId()).getFirst().registeredParticipants());
            assertTrue(report.participation(day.plusDays(1), null, beachId, event.getId()).isEmpty());
            authenticate(owner.getId().toString(), "VOLUNTEER");
            try { writer.update(first.getId(), input(owner, 4.001), first.getRowVersion()); }
            finally { SecurityContextHolder.clearContext(); }
            assertEquals(0, report.impact(day, day, beachId, materialId, null, "beach").totals().weightKg()
                .compareTo(new java.math.BigDecimal("6.252")));
            authenticate(owner.getId().toString(), "VOLUNTEER");
            try { writer.delete(first.getId(), context.getBean(TrashCollectionRepository.class).findById(first.getId()).orElseThrow().getRowVersion()); }
            finally { SecurityContextHolder.clearContext(); }
            assertEquals(0, report.impact(day, day, beachId, materialId, null, "beach").totals().weightKg()
                .compareTo(new java.math.BigDecimal("2.251")));
        }
    }

    @Test
    void auditPreservesTransfersAndDeletionEvidenceAcrossMySqlRestart() throws Exception {
        Long collectionId, ownerId, otherId, actorId;
        try (var context = start()) {
            var owner = account(context, "Original"); var other = account(context, "Destino");
            var actor = account(context, "Administrador");
            ownerId = owner.getId(); otherId = other.getId(); actorId = actor.getId();
            authenticate(actorId.toString(), "ADMIN");
            try {
                var writer = context.getBean(TrashCollectionService.class);
                var collection = writer.insert(input(owner, 2.125)); collectionId = collection.getId();
                var moved = writer.update(collectionId, input(other, 2.125), collection.getRowVersion());
                assertThrows(OptimisticLockingFailureException.class, () -> writer.delete(collectionId, collection.getRowVersion()));
                writer.delete(collectionId, moved.getRowVersion());
                var evidence = context.getBean(AuditService.class).search("COLLECTION", collectionId, actorId, "TRANSFER", null,null,0,20);
                assertEquals(1,evidence.totalElements());
                assertEquals(ownerId,evidence.content().getFirst().previousUserId);
                assertEquals(otherId,evidence.content().getFirst().nextUserId);
            } finally { SecurityContextHolder.clearContext(); }
        }
        try (var context = start(url,false)) {
            var evidence = context.getBean(AuditService.class).search("COLLECTION",collectionId,null,null,null,null,0,20);
            assertEquals(3,evidence.totalElements());
            assertEquals("DELETE",evidence.content().getFirst().action);
            assertEquals(actorId,evidence.content().getFirst().actorId);
            var service = context.getBean(UserService.class);
            for (Long id : List.of(ownerId,otherId,actorId)) assertThrows(
                com.raphael.beachcleanup.exceptions.EventStateConflictException.class,
                () -> service.delete(id,context.getBean(UserRepository.class).findById(id).orElseThrow().getRowVersion()));
        }
    }

    @Test
    void renewableSessionsPersistAndConcurrentReplayRevokesTheWinnerOnMySql() throws Exception {
        com.raphael.beachcleanup.dto.TokenDTO token;
        try (var context = start()) {
            account(context, "Ana");
            token = context.getBean(AuthService.class).login(new LoginDTO("ana@example.com", "senha-segura-forte"));
            var jdbc = context.getBean(JdbcTemplate.class);
            String stored = jdbc.queryForObject("select token_hash from tb_refresh_token", String.class);
            assertEquals(64, stored.length()); assertNotEquals(token.refreshToken(), stored);
        }
        try (var context = start()) {
            var auth = context.getBean(AuthService.class);
            var decoder = context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class);
            assertDoesNotThrow(() -> decoder.decode(token.accessToken()));
            var rotated = auth.refresh(token.refreshToken());
            assertNotEquals(token.refreshToken(), rotated.refreshToken());
            assertTrue(rotated.refreshExpiresIn() <= token.refreshExpiresIn());
            var independent = auth.login(new LoginDTO("ana@example.com", "senha-segura-forte"));
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<com.raphael.beachcleanup.dto.TokenDTO> renew = () -> {
                    await(start);
                    try { return auth.refresh(rotated.refreshToken()); }
                    catch (org.springframework.security.authentication.BadCredentialsException expected) { return null; }
                };
                var first = executor.submit(renew); var second = executor.submit(renew); start.countDown();
                var results = java.util.Arrays.asList(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(java.util.Objects::nonNull).count());
                var winner = results.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
                assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(winner.accessToken()));
                assertThrows(org.springframework.security.authentication.BadCredentialsException.class, () -> auth.refresh(winner.refreshToken()));
            }
            assertDoesNotThrow(() -> decoder.decode(independent.accessToken()));
            var next = auth.refresh(independent.refreshToken());
            auth.logout(independent.refreshToken()); // A consumed ancestor still identifies its family.
            assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(next.accessToken()));
            assertThrows(org.springframework.security.authentication.BadCredentialsException.class, () -> auth.refresh(next.refreshToken()));
        }
    }

    @Test
    void refreshExpiryAndPasswordChangeInvalidatePersistedSessionsOnMySql() throws Exception {
        try (var context = start()) {
            var owner = account(context, "Ana");
            var auth = context.getBean(AuthService.class);
            var decoder = context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class);
            var expired = auth.login(new LoginDTO("ana@example.com", "senha-segura-forte"));
            context.getBean(JdbcTemplate.class).update("update tb_auth_session set expires_at = '2020-01-01 00:00:00'");
            assertThrows(org.springframework.security.authentication.BadCredentialsException.class, () -> auth.refresh(expired.refreshToken()));
            assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(expired.accessToken()));
            var current = auth.login(new LoginDTO("ana@example.com", "senha-segura-forte"));
            var other = auth.login(new LoginDTO("ana@example.com", "senha-segura-forte"));
            auth.changePassword(decoder.decode(current.accessToken()), new com.raphael.beachcleanup.dto.ChangePasswordDTO("senha-segura-forte", "senha-nova-forte"));
            for (var token : List.of(current, other)) {
                assertThrows(org.springframework.security.authentication.BadCredentialsException.class, () -> auth.refresh(token.refreshToken()));
                assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(token.accessToken()));
            }
            assertDoesNotThrow(() -> auth.login(new LoginDTO("ana@example.com", "senha-nova-forte")));
            context.getBean(UserRepository.class).deleteById(owner.getId());
            assertEquals(0, context.getBean(JdbcTemplate.class).queryForObject("select count(*) from tb_refresh_token", Integer.class));
            assertEquals(0, context.getBean(JdbcTemplate.class).queryForObject("select count(*) from tb_auth_session", Integer.class));
        }
    }

    @Test
    void recoveryTokensAndRateLimitsAreAtomicAndPersistentOnMySql() throws Exception {
        int allowed;
        try (var context = start(url, true, true)) {
            var owner = account(context, "Ana");
            var actions = context.getBean(AccountActionService.class);
            var verification = actions.prepareMail(new AccountMailRequest(owner.getEmail(), AccountActionService.VERIFY));
            var verifyToken = accountLinkToken(verification.text());
            actions.verify(verifyToken);
            assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class, () -> actions.verify(verifyToken));
            var auth = context.getBean(AuthService.class);
            var session = auth.login(new LoginDTO(owner.getEmail(), "senha-segura-forte"));
            var expired = actions.prepareMail(new AccountMailRequest(owner.getEmail(), AccountActionService.RESET));
            context.getBean(JdbcTemplate.class).update("update tb_account_action_token set expires_at = '2020-01-01 00:00:00' where purpose = 'RESET_PASSWORD'");
            assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class, () -> actions.reset(accountLinkToken(expired.text()), "Outra senha muito forte!"));
            var reset = accountLinkToken(actions.prepareMail(new AccountMailRequest(owner.getEmail(), AccountActionService.RESET)).text());
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<Boolean> operation = () -> {
                    await(start);
                    try { actions.reset(reset, "Outra senha muito forte!"); return true; }
                    catch (com.raphael.beachcleanup.exceptions.FieldValidationException expected) { return false; }
                };
                var a = executor.submit(operation); var b = executor.submit(operation); start.countDown();
                assertEquals(1, List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)).stream().filter(Boolean::booleanValue).count());
            }
            assertThrows(org.springframework.security.oauth2.jwt.JwtException.class,
                () -> context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(session.accessToken()));
            assertThrows(org.springframework.security.authentication.BadCredentialsException.class, () -> auth.refresh(session.refreshToken()));
            var limiter = context.getBean(com.raphael.beachcleanup.security.AbuseLimiter.class);
            var barrier = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(8)) {
                var results = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
                for (int i = 0; i < 8; i++) results.add(executor.submit(() -> { await(barrier); return limiter.consume("test-race", "identity", 3, 900); }));
                barrier.countDown(); allowed = 0;
                for (var future : results) if (future.get(30, TimeUnit.SECONDS) == 0) allowed++;
                assertTrue(allowed > 0 && allowed <= 3);
            }
        }
        try (var context = start(url, true, true)) {
            assertTrue(context.getBean(UserRepository.class).findByEmail("ana@example.com").orElseThrow().isEmailVerified());
            context.getBean(AuthService.class).login(new LoginDTO("ana@example.com", "Outra senha muito forte!"));
            var limiter = context.getBean(com.raphael.beachcleanup.security.AbuseLimiter.class);
            for (int i = allowed; i < 3; i++) assertEquals(0, limiter.consume("test-race", "identity", 3, 900));
            assertTrue(limiter.consume("test-race", "identity", 3, 900) > 0);
        }
    }

    private String accountLinkToken(String text) {
        var matcher = java.util.regex.Pattern.compile("#(?:reset|verify)=([A-Za-z0-9_-]{43})").matcher(text);
        assertTrue(matcher.find()); return matcher.group(1);
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
        return start(databaseUrl, true);
    }

    private ConfigurableApplicationContext start(String databaseUrl, boolean seedCatalogs) {
        return start(databaseUrl, seedCatalogs, false);
    }

    private ConfigurableApplicationContext start(String databaseUrl, boolean seedCatalogs, boolean abuseEnabled) {
        var context = new SpringApplicationBuilder(BeachcleanupApplication.class).run(
            "--spring.profiles.active=prod", "--security.accounts.require-verified-email=false", "--security.abuse.enabled=" + abuseEnabled, "--server.port=0", "--spring.datasource.url=" + databaseUrl,
            "--spring.datasource.username=" + username, "--spring.datasource.password=" + password,
            "--security.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "--logging.level.root=WARN");
        if (seedCatalogs) CatalogTestData.seed(context);
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
            new User(null, name, name.toLowerCase(java.util.Locale.ROOT) + "@example.com", "senha-segura-forte", 0.0));
    }

    private static TrashCollection input(User owner, double weight) {
        var result = new TrashCollection(null, "Praia A", "Vidro", weight, LocalDate.parse("2026-10-15"));
        result.setVolunteer(owner);
        return result;
    }
}
