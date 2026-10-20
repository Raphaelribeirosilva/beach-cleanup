package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.mock.env.MockEnvironment;
import com.raphael.beachcleanup.config.AuthRetentionPolicy;
import com.raphael.beachcleanup.security.AbuseLimiter;
import com.raphael.beachcleanup.services.*;

@ExtendWith(OutputCaptureExtension.class)
class AuthMaintenanceSchedulingTests {
    @Configuration(proxyBeanMethods = false) @EnableScheduling @Import(AuthMaintenanceScheduler.class)
    static class Config {
        @Bean AuthDataMaintenance maintenance() {
            var service = mock(AuthDataMaintenance.class);
            when(service.cleanup()).thenThrow(new IllegalStateException("secret token recipient@example.com"))
                .thenReturn(new AuthDataMaintenance.Result(1, 2, 3));
            return service;
        }
    }
    @Test void periodicExecutionContinuesAfterFailureWithoutLoggingSensitiveException(CapturedOutput output) {
        new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues(
            "security.maintenance.fixed-delay-ms=50", "security.maintenance.initial-delay-ms=0").run(context -> {
                verify(context.getBean(AuthDataMaintenance.class), timeout(3000).atLeast(2)).cleanup();
            });
        assertFalse(output.getAll().contains("secret token"));
        assertFalse(output.getAll().contains("recipient@example.com"));
    }
    @Test void disablingPeriodicMaintenanceDoesNotStartTheScheduler() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
            .withPropertyValues("security.maintenance.enabled=false").run(context -> {
                assertEquals(0, context.getBeansOfType(AuthMaintenanceScheduler.class).size());
                verifyNoInteractions(context.getBean(AuthDataMaintenance.class));
            });
    }
    @Test void unsafeRetentionAndBatchSettingsAreRejected() {
        for (String property : java.util.List.of("session-retention-days", "action-token-retention-days",
                "rate-limit-retention-hours", "batch-size", "max-batches"))
            assertThrows(IllegalArgumentException.class, () -> new AuthRetentionPolicy(
                new MockEnvironment().withProperty("security.maintenance." + property, "0")), property);
        assertTrue(new AuthRetentionPolicy(new MockEnvironment()).rateLimits.getSeconds() >= AbuseLimiter.MAX_WINDOW_SECONDS);
    }
}
