package com.raphael.beachcleanup.config;

import java.net.URI;
import org.springframework.core.env.*;
import org.springframework.stereotype.Component;

@Component
public class AccountPolicy {
    public final boolean verifiedRequired, abuseEnabled, test, publicAccess;
    public final String mailMode, baseUrl, from;
    private final String brevoApiKey;
    public String brevoApiKey() { return brevoApiKey; }
    public AccountPolicy(Environment env) {
        test = env.acceptsProfiles(Profiles.of("test"));
        verifiedRequired = env.getProperty("security.accounts.require-verified-email", Boolean.class, !test);
        abuseEnabled = env.getProperty("security.abuse.enabled", Boolean.class, !test);
        publicAccess = env.getProperty("security.public-access.enabled", Boolean.class, false);
        mailMode = env.getProperty("account.mail.mode", test ? "memory" : "disabled");
        baseUrl = env.getProperty("account.public-base-url", test ? "http://localhost:8080" : "");
        from = env.getProperty("account.mail.from", "");
        brevoApiKey = env.getProperty("account.mail.brevo.api-key", "");
        if (env.acceptsProfiles(Profiles.of("gmail")) && (!mailMode.equals("smtp")
                || env.getProperty("spring.mail.username", "").isBlank()
                || env.getProperty("spring.mail.password", "").isBlank()))
            throw new IllegalStateException("O perfil Gmail exige GMAIL_USERNAME e GMAIL_APP_PASSWORD configurados no ambiente e envio SMTP ativo.");
        boolean localHttp = env.getProperty("account.mail.allow-local-http", Boolean.class, false)
            && !env.acceptsProfiles(Profiles.of("prod")) && !publicAccess
            && java.util.Set.of("localhost", "127.0.0.1").contains(env.getProperty("server.address", ""));
        if (!java.util.Set.of("memory", "smtp", "brevo", "disabled").contains(mailMode) || (!test && mailMode.equals("memory")))
            throw new IllegalStateException("Modo de e-mail inválido; simulação é permitida somente no perfil test.");
        if (!mailMode.equals("disabled")) {
            URI uri = URI.create(baseUrl);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || (test || localHttp) && "http".equals(uri.getScheme()) && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost())))
                throw new IllegalStateException("Configure ACCOUNT_PUBLIC_BASE_URL com uma origem HTTPS confiável.");
        }
        if (mailMode.equals("smtp") && (from.isBlank() || env.getProperty("spring.mail.host", "").isBlank()
                || !env.getProperty("spring.mail.protocol", "smtp").equals("smtp")
                || !env.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class, false)
                || !env.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", Boolean.class, false)))
            throw new IllegalStateException("SMTP exige remetente, host, STARTTLS obrigatório e validação de certificado.");
        if (mailMode.equals("brevo") && (from.isBlank() || brevoApiKey.isBlank()))
            throw new IllegalStateException("Brevo exige remetente verificado e chave de API no ambiente.");
        if (publicAccess && (test || !verifiedRequired || !abuseEnabled || !java.util.Set.of("smtp", "brevo").contains(mailMode)
                || !env.getProperty("account.mail.delivery-validated", Boolean.class, false)))
            throw new IllegalStateException("Abertura pública exige confirmação de e-mail, limites ativos e entrega real previamente validada.");
        if (verifiedRequired && mailMode.equals("disabled"))
            org.slf4j.LoggerFactory.getLogger(AccountPolicy.class).warn(
                "Confirmação de e-mail obrigatória, mas envio desativado. Configure SMTP ou Brevo HTTPS; para Gmail local, ative mysql,gmail-local com GMAIL_USERNAME e GMAIL_APP_PASSWORD. Contas não confirmadas não poderão entrar.");
    }
}
