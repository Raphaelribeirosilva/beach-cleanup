package com.raphael.beachcleanup.services;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "security.maintenance", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthMaintenanceScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AuthMaintenanceScheduler.class);
    private final AuthDataMaintenance maintenance;
    public AuthMaintenanceScheduler(AuthDataMaintenance maintenance) { this.maintenance = maintenance; }

    @Scheduled(fixedDelayString = "${security.maintenance.fixed-delay-ms:3600000}",
        initialDelayString = "${security.maintenance.initial-delay-ms:60000}")
    public void run() {
        try {
            var result = maintenance.cleanup();
            LOG.info("Manutenção de autenticação: sessões={}, tokens de conta={}, contadores={} removidos.",
                result.sessions(), result.actionTokens(), result.rateLimits());
        } catch (Exception ignored) {
            // Database exceptions can include identifiers; retain no credential or personal data in logs.
            LOG.warn("Manutenção de autenticação adiada por falha no banco; nova tentativa no próximo ciclo.");
        }
    }
}
