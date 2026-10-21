package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;
import com.raphael.beachcleanup.config.RenderConfig;

class RenderConfigTests {
    private MockEnvironment environment() throws Exception {
        var props = new Properties();
        try (var input = getClass().getResourceAsStream("/application-render.properties")) { props.load(input); }
        var env = new MockEnvironment().withProperty("DB_HOST", "demo.aivencloud.com")
            .withProperty("DB_PORT", "12345").withProperty("DB_TRUSTSTORE_URL", "file:/tmp/ca.p12")
            .withProperty("PORT", "18765");
        env.getPropertySources().addLast(new PropertiesPropertySource("render", props));
        env.setActiveProfiles("prod", "mysql", "render");
        return env;
    }
    @Test void resolvesEnvironmentPortAndVerifiedTlsWithoutLocalDatabaseDefaults() throws Exception {
        var env = environment();
        assertDoesNotThrow(() -> new RenderConfig(env));
        assertEquals("18765", env.getProperty("server.port"));
        assertEquals("0.0.0.0", env.getProperty("server.address"));
        assertTrue(env.getProperty("spring.datasource.url").contains("trustCertificateKeyStoreUrl=file:/tmp/ca.p12"));
        assertEquals("true", env.getProperty("security.accounts.require-verified-email"));
    }
    @Test void rejectsInsecureProfilesAndLocalOrInjectedDatabase() throws Exception {
        for (String host : new String[]{"localhost", "127.0.0.1", "demo.aivencloud.com?sslMode=DISABLED"}) {
            var env = environment().withProperty("DB_HOST", host);
            assertThrows(IllegalStateException.class, () -> new RenderConfig(env));
        }
        var env = environment(); env.setActiveProfiles("render", "test");
        assertThrows(IllegalStateException.class, () -> new RenderConfig(env));
        var insecure = environment().withProperty("spring.datasource.url", "jdbc:mysql://demo.aivencloud.com:12345/defaultdb?sslMode=REQUIRED");
        assertThrows(IllegalStateException.class, () -> new RenderConfig(insecure));
        var unverified = environment().withProperty("security.accounts.require-verified-email", "false");
        assertThrows(IllegalStateException.class, () -> new RenderConfig(unverified));
    }
}
