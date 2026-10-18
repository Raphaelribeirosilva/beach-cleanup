package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.mockito.ArgumentCaptor;
import com.raphael.beachcleanup.config.AccountPolicy;
import com.raphael.beachcleanup.services.AccountMailGateway;
import com.raphael.beachcleanup.security.PasswordPolicy;

class AccountPolicyTests {
    @Test void minimumPasswordCountsUnicodeAndRetainsTheBcryptByteLimit() {
        var policy = new PasswordPolicy();
        for (var password : java.util.List.of("Uma praia mais limpa!", "abcde🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊", "a".repeat(70) + "é"))
            assertDoesNotThrow(() -> policy.validate(password, "password"));
        for (var password : java.util.List.of("curta", "a".repeat(20), "123456789012345", "a".repeat(71) + "é"))
            assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class, () -> policy.validate(password, "password"));
    }
    @Test void publicReadinessFailsClosedWithoutRealDeliveryAndCannotUseTheTestProfile() {
        var env = new MockEnvironment().withProperty("security.public-access.enabled", "true");
        env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
        smtp(env);
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
        env.withProperty("account.mail.delivery-validated", "true");
        assertDoesNotThrow(() -> new AccountPolicy(env));
        env.withProperty("security.abuse.enabled", "false");
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
        env.withProperty("security.abuse.enabled", "true"); env.setActiveProfiles("test");
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
    }
    @Test void smtpAdapterMapsMessageAndRequiresTlsAndTrustedBaseUrl() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod"); smtp(env);
        var sender = mock(JavaMailSender.class);
        var factory = new DefaultListableBeanFactory(); factory.registerSingleton("mail", sender);
        var gateway = new AccountMailGateway(new AccountPolicy(env), factory.getBeanProvider(JavaMailSender.class));
        gateway.send(new AccountMailGateway.Mail("recipient@example.com", "Confirmar e-mail", "https://app.example/#verify=not-real"));
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class); verify(sender).send(message.capture());
        assertEquals("sender@example.com", message.getValue().getFrom());
        assertArrayEquals(new String[]{"recipient@example.com"}, message.getValue().getTo());
        assertEquals("Confirmar e-mail", message.getValue().getSubject());
        assertThrows(IllegalStateException.class, gateway::simulatedMessages);
        env.withProperty("spring.mail.protocol", "smtps");
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
        env.withProperty("spring.mail.protocol", "smtp");
        env.withProperty("spring.mail.properties.mail.smtp.starttls.required", "false");
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
        env.withProperty("spring.mail.properties.mail.smtp.starttls.required", "true").withProperty("account.public-base-url", "http://attacker.example");
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
        env.withProperty("account.mail.mode", "memory");
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
    }
    private void smtp(MockEnvironment env) {
        env.withProperty("account.mail.mode", "smtp").withProperty("account.public-base-url", "https://app.example")
            .withProperty("account.mail.from", "sender@example.com").withProperty("spring.mail.host", "smtp.example")
            .withProperty("spring.mail.properties.mail.smtp.starttls.required", "true")
            .withProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", "true");
    }
}
