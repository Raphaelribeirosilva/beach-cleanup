package db.migration.mysql;

import java.sql.*;
import java.text.Normalizer;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preserve original labels; only spelling normalization is automatic, never geographic inference. */
public class V6__backfill_reference_catalogs extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<String, Long> ids = new HashMap<>();
        backfill(connection, ids, "tb_cleanup_event", "location", "beach_id", "beaches");
        backfill(connection, ids, "tb_trash_collection", "beach", "beach_id", "beaches");
        backfill(connection, ids, "tb_trash_collection", "material_type", "material_id", "materials");
    }

    private void backfill(Connection connection, Map<String, Long> ids, String table, String label,
            String reference, String kind) throws SQLException {
        // Buffer rows before writing; do not depend on a JDBC driver's streaming cursor behavior.
        Map<Long, String> rows = new LinkedHashMap<>();
        try (var query = connection.createStatement();
                var result = query.executeQuery("select id," + label + " from " + table + " order by id")) {
            while (result.next()) rows.put(result.getLong(1), result.getString(2));
        }
        for (var row : rows.entrySet()) {
            String name = row.getValue() == null ? "" : row.getValue().replaceAll("[\\p{Z}\\s]+", " ").strip();
            if (name.isBlank()) continue; // Keep incomplete legacy records intact for administrative review.
            String key = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
            String compoundKey = kind + ":" + key;
            Long entryId = ids.get(compoundKey);
            if (entryId == null) {
                try (var insert = connection.prepareStatement("insert into tb_catalog_entry(kind,name) values (?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, kind);
                    insert.setString(2, name);
                    insert.executeUpdate();
                    try (var generated = insert.getGeneratedKeys()) {
                        if (!generated.next()) throw new SQLException("Catalog ID was not generated");
                        entryId = generated.getLong(1);
                    }
                }
                try (var alias = connection.prepareStatement(
                        "insert into tb_catalog_alias(kind,normalized_name,entry_id) values (?,?,?)")) {
                    alias.setString(1, kind);
                    alias.setString(2, key);
                    alias.setLong(3, entryId);
                    alias.executeUpdate();
                }
                ids.put(compoundKey, entryId);
            }
            try (var update = connection.prepareStatement("update " + table + " set " + reference + "=? where id=?")) {
                update.setLong(1, entryId);
                update.setLong(2, row.getKey());
                update.executeUpdate();
            }
        }
    }
}
