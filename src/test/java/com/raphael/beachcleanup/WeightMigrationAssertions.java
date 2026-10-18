package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;

/** The same legacy conversion assertions run against H2 and real MySQL. */
final class WeightMigrationAssertions {
    static void verify(String url, String username, String password) throws Exception {
        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration/mysql")
            .target("6").load().migrate();
        var values = List.of("2.3455", "0.0004", "100000.1234", "-2.3455", "0.0005", "0.30000000000000004");
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("senha-antiga");
        try (var connection = DriverManager.getConnection(url, username, password);
                var sql = connection.createStatement()) {
            try (var user = connection.prepareStatement("""
                    insert into tb_user(id,name,email,password,role,credential_version,row_version,total_collected_kg)
                    values (101,'Legado','legacy@example.com',?,'ADMIN',3,7,99.125)
                    """)) { user.setString(1, hash); user.executeUpdate(); }
            sql.executeUpdate("""
                insert into tb_cleanup_event(id,title,location,date_time,status,row_version)
                values (501,'Legado',null,'2020-01-02 08:00:00','Concluido',9)
                """);
            sql.executeUpdate("insert into tb_event_volunteer values (101,501)");
            try (var insert = connection.prepareStatement("""
                    insert into tb_trash_collection(id,beach,material_type,weight_kg,registration_date,volunteer_id,event_id,row_version)
                    values (?,null,' ',?,'2020-01-01',101,501,5)
                    """)) {
                for (int i = 0; i <= values.size(); i++) {
                    insert.setInt(1, 601 + i);
                    if (i == values.size()) insert.setNull(2, java.sql.Types.DOUBLE);
                    else insert.setDouble(2, Double.parseDouble(values.get(i)));
                    insert.executeUpdate();
                }
            }
        }
        var flyway = Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration/mysql").load();
        assertEquals(2, flyway.migrate().migrationsExecuted); // V7 weights and V8 refresh sessions.
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        BigDecimal before = BigDecimal.ZERO, after = BigDecimal.ZERO, delta = BigDecimal.ZERO;
        try (var connection = DriverManager.getConnection(url, username, password); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("""
                    select c.*,a.original_weight,a.rounded_weight,a.delta_weight from tb_trash_collection c
                    join tb_weight_conversion_audit a on c.id=a.collection_id order by c.id
                    """)) {
                for (String value : values) {
                    assertTrue(rows.next());
                    BigDecimal original = new BigDecimal(value);
                    BigDecimal rounded = original.setScale(3, RoundingMode.HALF_UP);
                    assertEquals(0, original.compareTo(new BigDecimal(rows.getString("original_weight"))));
                    assertEquals(rounded, rows.getBigDecimal("weight_kg"));
                    assertEquals(rounded, rows.getBigDecimal("rounded_weight"));
                    assertEquals(0, rounded.subtract(original).compareTo(new BigDecimal(rows.getString("delta_weight"))));
                    assertEquals(6, rows.getLong("row_version"));
                    assertEquals(101, rows.getLong("volunteer_id"));
                    assertEquals(501, rows.getLong("event_id"));
                    assertEquals("2020-01-01", rows.getDate("registration_date").toString());
                    assertNull(rows.getObject("beach_id"));
                    assertNull(rows.getObject("material_id"));
                    before = before.add(original); after = after.add(rounded);
                    delta = delta.add(new BigDecimal(rows.getString("delta_weight")));
                }
                assertTrue(rows.next());
                assertNull(rows.getBigDecimal("weight_kg"));
                assertNull(rows.getString("original_weight"));
                assertNull(rows.getString("delta_weight"));
                assertEquals(5, rows.getLong("row_version"));
                assertFalse(rows.next());
            }
            assertEquals(0, after.subtract(before).compareTo(delta), "Every total difference must be accounted for");
            assertEquals(new BigDecimal("100000.424"), after);
            try (var rows = sql.executeQuery("select * from tb_user where id=101")) {
                assertTrue(rows.next());
                assertEquals(hash, rows.getString("password"));
                assertEquals(3, rows.getLong("credential_version"));
                assertEquals(7, rows.getLong("row_version"));
                assertEquals("ADMIN", rows.getString("role"));
                assertEquals(99.125, rows.getDouble("total_collected_kg"));
            }
            try (var rows = sql.executeQuery("select count(*) from tb_event_volunteer where volunteer_id=101 and event_id=501")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
        }
    }
}
