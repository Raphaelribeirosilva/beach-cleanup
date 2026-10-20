package db.migration.mysql;

import java.sql.*;
import java.text.Normalizer;
import java.util.Locale;
import org.flywaydb.core.api.migration.*;

/** Add reviewed places, with city-qualified names; never guess the location of legacy records. */
public class V13__seed_beach_directory extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception { seed(context.getConnection()); }

    public static void seed(Connection connection) throws Exception {
        try (var source = V13__seed_beach_directory.class.getResourceAsStream("/db/beaches-v13.json")) {
            for (var city : new tools.jackson.databind.ObjectMapper().readTree(source)) {
                String uf = city.get("state").asText(), municipality = city.get("city").asText();
                for (var beach : city.get("beaches")) {
                    String name = beach.asText() + " — " + municipality + "/" + uf;
                    String key = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
                    // Existing city-qualified names (including merged aliases) remain authoritative.
                    try (var query = connection.prepareStatement("select entry_id from tb_catalog_alias where kind='beaches' and normalized_name=?")) {
                        query.setString(1, key);
                        try (var rows = query.executeQuery()) { if (rows.next()) continue; }
                    }
                    // A manually registered beach with the same name and location also takes precedence.
                    try (var query = connection.prepareStatement("select id from tb_catalog_entry where kind='beaches' and name=? and state=? and city=? and merged_into_id is null")) {
                        query.setString(1, beach.asText()); query.setString(2, uf); query.setString(3, municipality);
                        try (var rows = query.executeQuery()) { if (rows.next()) continue; }
                    }
                    long id;
                    try (var insert = connection.prepareStatement("insert into tb_catalog_entry(kind,name,state,city,version,updated_at) values ('beaches',?,?,?,0,CURRENT_TIMESTAMP)", Statement.RETURN_GENERATED_KEYS)) {
                        insert.setString(1, name); insert.setString(2, uf); insert.setString(3, municipality);
                        insert.executeUpdate();
                        try (var rows = insert.getGeneratedKeys()) { if (!rows.next()) throw new SQLException("Missing beach ID"); id = rows.getLong(1); }
                    }
                    try (var insert = connection.prepareStatement("insert into tb_catalog_alias(kind,normalized_name,entry_id) values ('beaches',?,?)")) {
                        insert.setString(1, key); insert.setLong(2, id); insert.executeUpdate();
                    }
                }
            }
        }
    }
}
