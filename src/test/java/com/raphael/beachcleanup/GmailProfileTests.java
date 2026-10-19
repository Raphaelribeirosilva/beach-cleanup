package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import com.raphael.beachcleanup.config.AccountPolicy;

class GmailProfileTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
        .withUserConfiguration(AccountPolicy.class);

    @Test
    void localProfileConfiguresGmailTlsCredentialsAndLoopbackWithoutOpeningNetworkConnections() {
        runner.withPropertyValues("spring.profiles.active=mysql,gmail-local",
            "GMAIL_USERNAME=sender@gmail.com", "GMAIL_APP_PASSWORD=not-a-real-password").run(context -> {
                assertNull(context.getStartupFailure());
                var policy = context.getBean(AccountPolicy.class);
                assertEquals("smtp", policy.mailMode);
                assertTrue(policy.verifiedRequired);
                assertTrue(policy.abuseEnabled);
                assertFalse(policy.publicAccess);
                assertEquals("http://localhost:8080", policy.baseUrl);
                assertEquals("127.0.0.1", context.getEnvironment().getProperty("server.address"));
                var sender = context.getBean(JavaMailSenderImpl.class);
                assertEquals("smtp.gmail.com",sender.getHost());
                assertEquals(587,sender.getPort());
                assertEquals("sender@gmail.com",sender.getUsername());
                assertEquals("not-a-real-password",sender.getPassword());
                assertEquals("true",sender.getJavaMailProperties().getProperty("mail.smtp.auth"));
                assertEquals("true",sender.getJavaMailProperties().getProperty("mail.smtp.starttls.required"));
                assertEquals("true",sender.getJavaMailProperties().getProperty("mail.smtp.ssl.checkserveridentity"));
                assertTrue(context.getEnvironment().getProperty("spring.mail.test-connection",Boolean.class));
            });
    }

    @Test
    void gmailProfileRequiresCredentialsInsteadOfSilentlyDisablingDelivery() {
        runner.withPropertyValues("spring.profiles.active=mysql,gmail-local",
            "GMAIL_USERNAME=sender@gmail.com", "GMAIL_APP_PASSWORD=").run(context ->
            assertNotNull(context.getStartupFailure()));
    }
}
