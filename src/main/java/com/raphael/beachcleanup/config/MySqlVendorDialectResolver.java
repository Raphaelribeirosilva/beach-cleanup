package com.raphael.beachcleanup.config;

import java.util.regex.Pattern;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolver;

/** Connector/J may advertise MariaDB as MySQL 5.5.5; retain the real vendor/version. */
public class MySqlVendorDialectResolver implements DialectResolver {
    private static final Pattern MARIA_VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)-MariaDB", Pattern.CASE_INSENSITIVE);

    @Override
    public Dialect resolveDialect(DialectResolutionInfo info) {
        if (!"MySQL".equalsIgnoreCase(info.getDatabaseName()) || info.getDatabaseVersion() == null) return null;
        var version = MARIA_VERSION.matcher(info.getDatabaseVersion());
        if (!version.find()) return null;
        return new MariaDBDialect(DatabaseVersion.make(Integer.parseInt(version.group(1)),
            Integer.parseInt(version.group(2)), Integer.parseInt(version.group(3))));
    }
}
