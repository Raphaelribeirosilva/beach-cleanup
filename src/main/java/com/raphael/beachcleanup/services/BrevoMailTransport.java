package com.raphael.beachcleanup.services;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.raphael.beachcleanup.config.AccountPolicy;

/** HTTPS with platform trust, bounded timeouts and no redirect of API credentials. */
public final class BrevoMailTransport {
    private final RestClient client;
    private final AccountPolicy policy;

    public BrevoMailTransport(AccountPolicy policy) { this(policy, builder()); }

    public BrevoMailTransport(AccountPolicy policy, RestClient.Builder builder) {
        this.policy = policy;
        client = builder.baseUrl("https://api.brevo.com/v3")
            .defaultHeader("api-key", policy.brevoApiKey()).build();
    }

    private static RestClient.Builder builder() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory);
    }

    public void send(AccountMailGateway.Mail mail) {
        client.post().uri("/smtp/email").contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("sender", Map.of("name", "Elo do Mar", "email", policy.from),
                "to", List.of(Map.of("email", mail.recipient())), "subject", mail.subject(),
                "textContent", mail.text()))
            .exchange((request, response) -> {
                // Never retain provider bodies: they can echo addresses, tokens or credentials.
                if (response.getStatusCode().value() != 201)
                    throw new IllegalStateException("API de e-mail recusou o envio; status=" + response.getStatusCode().value());
                return null;
            });
    }
}
