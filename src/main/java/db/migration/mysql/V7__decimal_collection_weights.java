package db.migration.mysql;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Snapshot before DDL; never rely on an implicit DOUBLE -> DECIMAL rounding policy. */
public class V7__decimal_collection_weights extends BaseJavaMigration {
    private record Weight(long id, Long owner, String original, BigDecimal rounded, String delta) {}

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        var weights = new ArrayList<Weight>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("select id,volunteer_id,weight_kg from tb_trash_collection order by id")) {
            while (rows.next()) {
                long id = rows.getLong("id");
                Long owner = rows.getObject("volunteer_id") == null ? null : rows.getLong("volunteer_id");
                if (rows.getObject("weight_kg") == null) {
                    weights.add(new Weight(id, owner, null, null, null));
                    continue;
                }
                double value = rows.getDouble("weight_kg");
                if (!Double.isFinite(value)) throw new SQLException("V7: peso não finito na coleta " + id + "; revise antes de migrar.");
                BigDecimal original = BigDecimal.valueOf(value);
                BigDecimal rounded = original.setScale(3, RoundingMode.HALF_UP);
                if (rounded.precision() > 38) {
                    throw new SQLException("V7: peso fora de DECIMAL(38,3) na coleta " + id + "; nenhuma conversão foi iniciada.");
                }
                weights.add(new Weight(id, owner, original.toString(), rounded, rounded.subtract(original).toString()));
            }
        }
        // MySQL DDL commits implicitly. Preflight every value before creating the audit table or altering data.
        try (var sql = connection.createStatement()) {
            sql.executeUpdate("""
                create table tb_weight_conversion_audit (
                    collection_id BIGINT NOT NULL PRIMARY KEY,
                    volunteer_id BIGINT,
                    original_weight VARCHAR(64),
                    rounded_weight DECIMAL(38,3),
                    delta_weight VARCHAR(96)
                )
                """);
        }
        try (var audit = connection.prepareStatement("insert into tb_weight_conversion_audit values (?,?,?,?,?)")) {
            for (var weight : weights) {
                audit.setLong(1, weight.id());
                audit.setObject(2, weight.owner());
                audit.setString(3, weight.original());
                audit.setBigDecimal(4, weight.rounded());
                audit.setString(5, weight.delta());
                audit.addBatch();
            }
            audit.executeBatch();
        }
        try (var sql = connection.createStatement()) {
            boolean mysql = connection.getMetaData().getDatabaseProductName().equals("MySQL");
            sql.executeUpdate("alter table tb_trash_collection " + (mysql ? "modify column" : "alter column")
                + " weight_kg DECIMAL(38,3)");
        }
        try (var update = connection.prepareStatement("""
                update tb_trash_collection set weight_kg=?, row_version=row_version+? where id=?
                """)) {
            for (var weight : weights) {
                update.setBigDecimal(1, weight.rounded());
                update.setInt(2, weight.delta() != null && new BigDecimal(weight.delta()).signum() != 0 ? 1 : 0);
                update.setLong(3, weight.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
