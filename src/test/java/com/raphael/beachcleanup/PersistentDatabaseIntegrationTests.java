package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.raphael.beachcleanup.dto.LoginDTO;
import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.services.AuthService;
import com.raphael.beachcleanup.services.UserService;
import com.raphael.beachcleanup.services.CleanupEventService;
import com.raphael.beachcleanup.services.TrashCollectionService;
import com.raphael.beachcleanup.services.EventRegistrationService;
import com.raphael.beachcleanup.services.CollectedTotalsService;

class PersistentDatabaseIntegrationTests {
    @TempDir Path directory;

    @Test
    void collectionVersionMigrationPreservesExistingRowsAndLinks() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("migrationdb").toString().replace('\\', '/')
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/mysql")
            .target("2").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var sql = connection.createStatement()) {
            sql.executeUpdate("""
                insert into tb_user(id,name,email,password,role,credential_version,row_version,total_collected_kg)
                values (101,'Ana','ana@example.com','hash-preservado','VOLUNTEER',7,3,99)
                """);
            sql.executeUpdate("""
                insert into tb_cleanup_event(id,title,location,date_time,status)
                values (202,'Mutirao','Praia A','2026-10-15 08:00:00','Agendado')
                """);
            sql.executeUpdate("insert into tb_event_volunteer(volunteer_id,event_id) values (101,202)");
            sql.executeUpdate("""
                insert into tb_trash_collection(id,beach,material_type,weight_kg,registration_date,volunteer_id,event_id)
                values (303,'Praia A','Vidro',2.5,'2026-10-15',101,202)
                """);
        }
        try (var context = start(url)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(6, context.getBean(Flyway.class).info().applied().length);
            assertEquals(0L, jdbc.queryForObject(
                "select row_version from tb_trash_collection where id=303", Long.class));
            assertEquals(0L, jdbc.queryForObject(
                "select row_version from tb_cleanup_event where id=202", Long.class));
            assertEquals(2.5, jdbc.queryForObject(
                "select weight_kg from tb_trash_collection where id=303", Double.class));
            assertEquals(101L, jdbc.queryForObject(
                "select volunteer_id from tb_trash_collection where id=303", Long.class));
            assertEquals(202L, jdbc.queryForObject(
                "select event_id from tb_trash_collection where id=303", Long.class));
            assertEquals("Vidro", jdbc.queryForObject(
                "select material_type from tb_trash_collection where id=303", String.class));
            assertEquals(LocalDate.parse("2026-10-15"), jdbc.queryForObject(
                "select registration_date from tb_trash_collection where id=303", java.sql.Date.class).toLocalDate());
            var user = context.getBean(UserRepository.class).findById(101L).orElseThrow();
            assertEquals("hash-preservado", user.getPassword());
            assertEquals(7L, user.getCredentialVersion());
            assertEquals(1, jdbc.queryForObject("select count(*) from tb_event_volunteer", Integer.class));
            assertEquals(2.5, context.getBean(CollectedTotalsService.class).user(user).getTotalCollectedKg());
        }
    }

    @Test
    void migrationsValidateSchemaAndDataSurviveRestartWithoutTestSeed() {
        String url = "jdbc:h2:file:" + directory.resolve("persistentdb").toString().replace('\\', '/')
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        long userId;
        long eventId;
        try (var context = start(url)) {
            var users = context.getBean(UserRepository.class);
            assertEquals(0, users.count());
            User user = context.getBean(UserService.class).insert(
                new User(null, "Ana", "ana@example.com", "senha-segura", 0.0));
            userId = user.getId();
            CleanupEvent event = context.getBean(CleanupEventService.class).insert(new CleanupEvent(null,
                "Mutirao", "Praia A", LocalDateTime.parse("2026-10-15T08:00:00"), "Agendado"));
            eventId = event.getId();
            context.getBean(EventRegistrationService.class).register(userId, eventId);
            var collection = new TrashCollection(null, "Praia A", "Vidro", 2.5, LocalDate.parse("2026-10-15"));
            collection.setVolunteer(user);
            collection.setEvent(event);
            context.getBean(TrashCollectionService.class).insert(collection);
            assertEquals(6, context.getBean(Flyway.class).info().applied().length);
            assertThrows(FlywayException.class, () -> context.getBean(Flyway.class).clean());
            assertFalse(context.getEnvironment().getProperty("spring.jpa.open-in-view", Boolean.class));
        }
        try (var context = start(url)) {
            var users = context.getBean(UserRepository.class);
            assertEquals(1, users.count());
            assertEquals("ana@example.com", users.findById(userId).orElseThrow().getEmail());
            assertEquals(eventId, context.getBean(CleanupEventRepository.class).findAll().getFirst().getId());
            assertEquals(2.5, context.getBean(CollectedTotalsService.class)
                .user(users.findById(userId).orElseThrow()).getTotalCollectedKg());
            assertEquals(eventId, context.getBean(EventRegistrationService.class).findMyEvents(userId).getFirst().id());
            assertEquals(userId, context.getBean(AuthService.class)
                .login(new LoginDTO("ana@example.com", "senha-segura")).userId());
            assertEquals(6, context.getBean(Flyway.class).info().applied().length);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into tb_user(name,email,password,role,credential_version)
                values ('Outra Ana','ana@example.com','hash','VOLUNTEER',0)
                """));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into tb_event_volunteer(volunteer_id,event_id) values (?,?)", userId, eventId));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into tb_event_volunteer(volunteer_id,event_id) values (?,?)", userId, 999999));
        }
    }

    private ConfigurableApplicationContext start(String url) {
        var context = new SpringApplicationBuilder(BeachcleanupApplication.class).run(
            "--spring.profiles.active=mysql", "--server.port=0",
            "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.url=" + url,
            "--spring.datasource.username=sa", "--spring.datasource.password=",
            "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "--security.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "--logging.level.root=WARN");
        CatalogTestData.seed(context);
        return context;
    }
}
