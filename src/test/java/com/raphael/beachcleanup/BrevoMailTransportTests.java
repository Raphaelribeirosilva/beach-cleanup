package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.raphael.beachcleanup.config.AccountPolicy;
import com.raphael.beachcleanup.services.*;

class BrevoMailTransportTests {
    private MockEnvironment environment() {
        var env = new MockEnvironment().withProperty("account.mail.mode", "brevo")
            .withProperty("account.mail.from", "sender@example.com")
            .withProperty("account.mail.brevo.api-key", "dummy-test-key")
            .withProperty("account.public-base-url", "https://demo.example.com");
        env.setActiveProfiles("prod", "mysql");
        return env;
    }

    @Test void sendsVerificationAndRecoveryContentOverHttps() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var transport = new BrevoMailTransport(new AccountPolicy(environment()), builder);
        for (String purpose : new String[]{"Confirmar e-mail", "Redefinir senha"}) {
            server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("api-key", "dummy-test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sender.email").value("sender@example.com"))
                .andExpect(jsonPath("$.to[0].email").value("recipient@example.com"))
                .andExpect(jsonPath("$.subject").value(purpose))
                .andExpect(jsonPath("$.textContent").value("https://demo.example.com/#token=example"))
                .andRespond(withStatus(HttpStatus.CREATED));
            transport.send(new AccountMailGateway.Mail("recipient@example.com", purpose,
                "https://demo.example.com/#token=example"));
            server.verify(); server.reset();
        }
    }

    @Test void providerErrorsAndRedirectsFailWithoutEchoingSensitiveResponse() {
        for (var status : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.FOUND}) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(status).body("sensitive-provider-response"));
            var transport = new BrevoMailTransport(new AccountPolicy(environment()), builder);
            var error = assertThrows(IllegalStateException.class, () -> transport.send(
                new AccountMailGateway.Mail("recipient@example.com", "subject", "token")));
            assertFalse(error.getMessage().contains("sensitive"));
            assertTrue(error.getMessage().contains(Integer.toString(status.value())));
            server.verify();
        }
    }

    @Test void publicAccessRequiresValidatedDeliveryAndConfiguredApi() {
        var env = environment().withProperty("security.public-access.enabled", "true");
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
        env.withProperty("account.mail.delivery-validated", "true");
        assertDoesNotThrow(() -> new AccountPolicy(env));
        env.withProperty("account.mail.brevo.api-key", "");
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
        env.withProperty("account.mail.brevo.api-key", "dummy-test-key").withProperty("account.mail.from", "");
        assertThrows(IllegalStateException.class, () -> new AccountPolicy(env));
    }
}
