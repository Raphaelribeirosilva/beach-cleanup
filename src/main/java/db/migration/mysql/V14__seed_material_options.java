package db.migration.mysql;

import java.sql.Connection;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Supply selectable materials without recreating renamed or merged catalog entries. */
public class V14__seed_material_options extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception { seed(context.getConnection()); }

    public static void seed(Connection connection) throws Exception {
        for (String name : List.of("Plástico", "Vidro", "Metal", "Papel", "Papelão", "Borracha", "Tecido", "Resíduos orgânicos", "Resíduos mistos")) {
            String key = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
            try (var query = connection.prepareStatement("select entry_id from tb_catalog_alias where kind='materials' and normalized_name=?")) {
                query.setString(1, key);
                try (var rows = query.executeQuery()) { if (rows.next()) continue; }
            }
            long id;
            try (var insert = connection.prepareStatement("insert into tb_catalog_entry(kind,name,version,updated_at) values ('materials',?,0,CURRENT_TIMESTAMP)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, name);
                insert.executeUpdate();
                try (var rows = insert.getGeneratedKeys()) {
                    if (!rows.next()) throw new java.sql.SQLException("Missing material ID");
                    id = rows.getLong(1);
                }
            }
            try (var insert = connection.prepareStatement("insert into tb_catalog_alias(kind,normalized_name,entry_id) values ('materials',?,?)")) {
                insert.setString(1, key); insert.setLong(2, id); insert.executeUpdate();
            }
        }
    }
}
