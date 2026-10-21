package com.raphael.beachcleanup.config;

import com.raphael.beachcleanup.services.DemoDataService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "demo.data.enabled", havingValue = "true")
public class DemoDataConfig {
    @Bean
    ApplicationRunner demoDataRunner(DemoDataService service) {
        return args -> service.seed();
    }
}
