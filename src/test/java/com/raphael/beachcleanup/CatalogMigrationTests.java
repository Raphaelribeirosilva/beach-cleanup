package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogMigrationTests {
    @Test
    void defaultMaterialsAreAvailableAndSeedingKeepsKnownNames() throws Exception {
        String url = "jdbc:h2:mem:material-options;MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/mysql").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("update tb_catalog_entry set name='Plástico reciclável' where kind='materials' and name='Plástico'");
            db.migration.mysql.V14__seed_material_options.seed(connection);
            try (var rows = sql.executeQuery("select count(*) from tb_catalog_entry where kind='materials'")) {
                assertTrue(rows.next()); assertEquals(9, rows.getInt(1));
            }
            try (var rows = sql.executeQuery("select name from tb_catalog_entry where id=(select entry_id from tb_catalog_alias where kind='materials' and normalized_name='plastico')")) {
                assertTrue(rows.next()); assertEquals("Plástico reciclável", rows.getString(1));
            }
        }
    }

    @Test
    void directoryAddsRealBeachesWithoutChangingLegacyPlacesAndCanBeReapplied() throws Exception {
        String directoryUrl = "jdbc:h2:mem:directory-migration;MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(directoryUrl, "sa", "").locations("classpath:db/migration/mysql").target("12").load().migrate();
        try (var connection = DriverManager.getConnection(directoryUrl, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("insert into tb_catalog_entry(id,kind,name) values (99001,'beaches','Praia do Morro')");
            sql.executeUpdate("insert into tb_catalog_alias values ('beaches','praia do morro',99001)");
            sql.executeUpdate("insert into tb_catalog_entry(id,kind,name,state,city) values (99002,'beaches','Praia do Gonzaga','SP','Santos')");
            sql.executeUpdate("insert into tb_catalog_alias values ('beaches','praia do gonzaga',99002)");
        }
        Flyway.configure().dataSource(directoryUrl, "sa", "").locations("classpath:db/migration/mysql").load().migrate();
        try (var connection = DriverManager.getConnection(directoryUrl, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select name,state,city from tb_catalog_entry where id=99001")) {
                assertTrue(rows.next()); assertEquals("Praia do Morro", rows.getString(1));
                assertNull(rows.getString(2)); assertNull(rows.getString(3));
            }
            try (var rows = sql.executeQuery("select count(*) from tb_catalog_entry where state='ES' and city='Guarapari'")) {
                assertTrue(rows.next()); assertTrue(rows.getInt(1) >= 15);
            }
            try (var rows = sql.executeQuery("select count(*) from tb_catalog_entry where state='SP' and city='Santos' and name like 'Praia do Gonzaga%'")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
            long before;
            try (var rows = sql.executeQuery("select count(*) from tb_catalog_entry")) { rows.next(); before = rows.getLong(1); }
            db.migration.mysql.V13__seed_beach_directory.seed(connection);
            try (var rows = sql.executeQuery("select count(*) from tb_catalog_entry")) { rows.next(); assertEquals(before, rows.getLong(1)); }
        }
    }
    @TempDir Path directory;

    @Test
    void migrationPreservesLegacyDataAndGroupsOnlyUnambiguousSpellingVariants() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("catalog-migration").toString().replace('\\', '/')
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/mysql")
            .target("4").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("""
                insert into tb_user(id,name,email,password,role,credential_version,row_version)
                values (101,'Ana','ana@example.com','hash-original','VOLUNTEER',3,7)
                """);
            sql.executeUpdate("""
                insert into tb_cleanup_event(id,title,location,date_time,status,row_version) values
                (501,'Mutirão A','Praia do Morro','2026-10-15 08:00:00','Agendado',7),
                (502,'Mutirão B','  PRAIA   DO MORRO ','2026-10-16 08:00:00','Concluido',8),
                (503,'Mutirão C','Morro','2026-10-17 08:00:00','Pausado',9)
                """);
            sql.executeUpdate("insert into tb_event_volunteer(volunteer_id,event_id) values (101,501)");
            sql.executeUpdate("""
                insert into tb_trash_collection(id,beach,material_type,weight_kg,registration_date,volunteer_id,event_id,row_version) values
                (601,'Praia do Morro','Plástico',2,'2026-10-15',101,501,5),
                (602,'praia do morro',' PLASTICO ',3,'2026-10-16',101,502,6),
                (603,'Morro','Vidro',4,'2026-10-17',101,null,7),
                (604,null,' ',2,'2026-10-18',101,null,8)
                """);
        }
        var flyway = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/mysql").load();
        flyway.migrate();
        assertEquals(16, flyway.info().applied().length);
        flyway.validate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select * from tb_trash_collection order by id")) {
                assertTrue(rows.next());
                long beach = rows.getLong("beach_id");
                long material = rows.getLong("material_id");
                assertTrue(beach > 0 && material > 0);
                assertEquals(601, rows.getLong("id"));
                assertEquals(101, rows.getLong("volunteer_id"));
                assertEquals(501, rows.getLong("event_id"));
                assertEquals(5, rows.getLong("row_version"));
                assertEquals("Plástico", rows.getString("material_type"));
                assertEquals("2026-10-15", rows.getDate("registration_date").toString());
                assertEquals(2, rows.getDouble("weight_kg"));
                assertTrue(rows.next());
                assertEquals(beach, rows.getLong("beach_id"));
                assertEquals(material, rows.getLong("material_id"));
                assertEquals("praia do morro", rows.getString("beach"));
                assertEquals(" PLASTICO ", rows.getString("material_type"));
                assertTrue(rows.next());
                assertNotEquals(beach, rows.getLong("beach_id"), "Similar geographic names require an explicit administrator decision");
                assertTrue(rows.next());
                assertNull(rows.getObject("beach_id"));
                assertNull(rows.getObject("material_id"));
                assertEquals(8, rows.getLong("row_version"));
                assertFalse(rows.next());
            }
            try (var result = sql.executeQuery("select count(distinct beach_id) from tb_cleanup_event")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
            try (var result = sql.executeQuery("select sum(weight_kg),count(*) from tb_trash_collection")) {
                assertTrue(result.next());
                assertEquals(11, result.getDouble(1));
                assertEquals(4, result.getInt(2));
            }
            try (var result = sql.executeQuery("select password,credential_version,row_version from tb_user where id=101")) {
                assertTrue(result.next());
                assertEquals("hash-original", result.getString(1));
                assertEquals(3, result.getLong(2));
                assertEquals(7, result.getLong(3));
            }
            try (var result = sql.executeQuery("select count(*) from tb_event_volunteer where volunteer_id=101 and event_id=501")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }
}
