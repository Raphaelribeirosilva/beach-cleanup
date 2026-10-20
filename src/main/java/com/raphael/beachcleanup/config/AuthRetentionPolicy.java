package com.raphael.beachcleanup.config;

import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import com.raphael.beachcleanup.security.AbuseLimiter;

@Component
public class AuthRetentionPolicy {
    public final Duration sessions, actionTokens, rateLimits;
    public final int batchSize, maxBatches;

    public AuthRetentionPolicy(Environment env) {
        int sessionDays = env.getProperty("security.maintenance.session-retention-days", Integer.class, 7);
        int tokenDays = env.getProperty("security.maintenance.action-token-retention-days", Integer.class, 7);
        int rateHours = env.getProperty("security.maintenance.rate-limit-retention-hours", Integer.class, 24);
        batchSize = env.getProperty("security.maintenance.batch-size", Integer.class, 200);
        maxBatches = env.getProperty("security.maintenance.max-batches", Integer.class, 10);
        if (sessionDays < 1 || sessionDays > 3650 || tokenDays < 1 || tokenDays > 3650
                || rateHours < 1 || rateHours > 87600
                || Duration.ofHours(rateHours).getSeconds() < AbuseLimiter.MAX_WINDOW_SECONDS
                || batchSize < 1 || batchSize > 2000 || maxBatches < 1 || maxBatches > 100)
            throw new IllegalArgumentException("Configuração inválida da retenção de autenticação.");
        sessions = Duration.ofDays(sessionDays);
        actionTokens = Duration.ofDays(tokenDays);
        rateLimits = Duration.ofHours(rateHours);
    }
}
