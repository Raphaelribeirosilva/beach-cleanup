package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.raphael.beachcleanup.config.ProductionConfig;

class ProductionConfigTests {
    @Test
    void validPersistentConfigurationIsAccepted() {
        assertDoesNotThrow(() -> new ProductionConfig(valid()));
    }

    @Test
    void externalMigrationsMustBeExplicitAndCannotRunAutomatically() {
        assertDoesNotThrow(() -> new ProductionConfig(valid()
            .withProperty("spring.flyway.enabled", "false")
            .withProperty("security.database.external-migrations", "true")));
        assertThrows(IllegalStateException.class, () -> new ProductionConfig(valid()
            .withProperty("security.database.external-migrations", "true")));
    }

    @Test
    void testProfileAndMissingMysqlProfileAreRejected() {
        var test = valid();
        test.setActiveProfiles("prod", "mysql", "test");
        assertThrows(IllegalStateException.class, () -> new ProductionConfig(test));
        var missing = valid();
        missing.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new ProductionConfig(missing));
    }

    @Test
    void destructiveOrTemporaryDatabaseOverridesAreRejected() {
        String[][] overrides = {
            {"spring.datasource.url", "jdbc:h2:mem:unexpected"},
            {"spring.datasource.driver-class-name", "org.h2.Driver"},
            {"spring.jpa.hibernate.ddl-auto", "create-drop"},
            {"spring.flyway.enabled", "false"},
            {"spring.flyway.clean-disabled", "false"},
            {"spring.h2.console.enabled", "true"}
        };
        for (var override : overrides) {
            var env = valid().withProperty(override[0], override[1]);
            assertThrows(IllegalStateException.class, () -> new ProductionConfig(env), override[0]);
        }
    }

    private MockEnvironment valid() {
        var env = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:mysql://localhost/test")
            .withProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")
            .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
            .withProperty("spring.flyway.enabled", "true")
            .withProperty("spring.flyway.clean-disabled", "true")
            .withProperty("spring.h2.console.enabled", "false");
        env.setActiveProfiles("prod", "mysql");
        return env;
    }
}
