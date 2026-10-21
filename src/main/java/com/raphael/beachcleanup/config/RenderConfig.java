package com.raphael.beachcleanup.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@Profile("render")
public class RenderConfig {
    public RenderConfig(Environment env) {
        if (!env.acceptsProfiles(Profiles.of("prod")) || env.acceptsProfiles(Profiles.of("test", "gmail", "gmail-local")))
            throw new IllegalStateException("Render exige prod e não permite test ou Gmail SMTP.");
        String url = env.getRequiredProperty("spring.datasource.url");
        // Only accept the fixed URL composed by the Render profile, without JDBC overrides.
        String host = env.getRequiredProperty("DB_HOST");
        String port = env.getRequiredProperty("DB_PORT");
        String database = env.getProperty("DB_NAME", "defaultdb");
        String store = env.getRequiredProperty("DB_TRUSTSTORE_URL");
        String expected = "jdbc:mysql://" + host + ":" + port + "/" + database
            + "?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=" + store
            + "&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=changeit"
            + "&fallbackToSystemTrustStore=false&allowPublicKeyRetrieval=false";
        if (!host.matches("[a-zA-Z0-9.-]+\\.aivencloud\\.com") || !port.matches("[0-9]{1,5}")
                || !database.matches("[a-zA-Z0-9_]+")
                || !store.matches("file:/tmp/[a-zA-Z0-9./_-]+\\.p12") || !url.equals(expected)
                || !env.getProperty("security.accounts.require-verified-email", Boolean.class, false)
                || !env.getProperty("security.abuse.enabled", Boolean.class, false)
                || !"brevo".equals(env.getProperty("account.mail.mode")))
            throw new IllegalStateException("Use a configuração Render com host Aiven, TLS VERIFY_IDENTITY e e-mail HTTPS.");
    }
}
