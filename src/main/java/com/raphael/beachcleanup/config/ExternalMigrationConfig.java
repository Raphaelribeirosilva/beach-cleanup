package com.raphael.beachcleanup.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Read-only validation for a runtime account without migration privileges. */
@Configuration
@Profile("prod")
@ConditionalOnProperty(name = "security.database.external-migrations", havingValue = "true")
public class ExternalMigrationConfig {
    @Bean
    SmartInitializingSingleton verifyExternalMigrations(DataSource dataSource) {
        return () -> {
            var flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/mysql").cleanDisabled(true)
                .ignoreMigrationPatterns(new String[0]).load();
            flyway.validate();
            if (flyway.info().pending().length != 0) {
                throw new IllegalStateException("Execute as migrações antes de iniciar a aplicação.");
            }
        };
    }
}
