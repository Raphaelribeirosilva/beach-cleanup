package com.raphael.beachcleanup.config;

import java.net.URI;
import org.springframework.core.env.*;
import org.springframework.stereotype.Component;

@Component
public class AccountPolicy {
    public final boolean verifiedRequired, abuseEnabled, test, publicAccess;
    public final String mailMode, baseUrl, from;
    public AccountPolicy(Environment env) {
        test = env.acceptsProfiles(Profiles.of("test"));
        verifiedRequired = env.getProperty("security.accounts.require-verified-email", Boolean.class, !test);
        abuseEnabled = env.getProperty("security.abuse.enabled", Boolean.class, !test);
        publicAccess = env.getProperty("security.public-access.enabled", Boolean.class, false);
        mailMode = env.getProperty("account.mail.mode", test ? "memory" : "disabled");
        baseUrl = env.getProperty("account.public-base-url", test ? "http://localhost:8080" : "");
        from = env.getProperty("account.mail.from", "");
        if (!java.util.Set.of("memory", "smtp", "disabled").contains(mailMode) || (!test && mailMode.equals("memory")))
            throw new IllegalStateException("Modo de e-mail inválido; simulação é permitida somente no perfil test.");
        if (!mailMode.equals("disabled")) {
            URI uri = URI.create(baseUrl);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || test && "http".equals(uri.getScheme()) && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost())))
                throw new IllegalStateException("Configure ACCOUNT_PUBLIC_BASE_URL com uma origem HTTPS confiável.");
        }
        if (mailMode.equals("smtp") && (from.isBlank() || env.getProperty("spring.mail.host", "").isBlank()
                || !env.getProperty("spring.mail.protocol", "smtp").equals("smtp")
                || !env.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class, false)
                || !env.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", Boolean.class, false)))
            throw new IllegalStateException("SMTP exige remetente, host, STARTTLS obrigatório e validação de certificado.");
        if (publicAccess && (test || !verifiedRequired || !abuseEnabled || !mailMode.equals("smtp")
                || !env.getProperty("account.mail.delivery-validated", Boolean.class, false)))
            throw new IllegalStateException("Abertura pública exige confirmação de e-mail, limites ativos e entrega SMTP real previamente validada.");
    }
}
