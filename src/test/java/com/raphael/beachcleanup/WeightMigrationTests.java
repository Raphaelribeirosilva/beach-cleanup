package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeightMigrationTests {
    @TempDir Path directory;
    private String url(String name) {
        return "jdbc:h2:file:" + directory.resolve(name).toString().replace('\\', '/')
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    }
    @Test
    void recordsAndRoundingDifferencesRemainAuditable() throws Exception {
        WeightMigrationAssertions.verify(url("rounding"), "sa", "");
    }
    @Test
    void unrepresentableWeightsFailBeforeAnyDomainDataOrSchemaChanges() throws Exception {
        String url = url("overflow");
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/mysql").target("6").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("insert into tb_trash_collection(id,weight_kg,row_version) values (1,1e100,8)");
        }
        assertThrows(org.flywaydb.core.api.FlywayException.class,
            () -> Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/mysql").load().migrate());
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select weight_kg,row_version from tb_trash_collection where id=1")) {
                assertTrue(rows.next()); assertEquals(1e100, rows.getDouble(1)); assertEquals(8, rows.getLong(2));
                assertEquals(java.sql.Types.DOUBLE, rows.getMetaData().getColumnType(1));
            }
            try (var rows = sql.executeQuery("select count(*) from information_schema.tables where lower(table_name)='tb_weight_conversion_audit'")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }
}
