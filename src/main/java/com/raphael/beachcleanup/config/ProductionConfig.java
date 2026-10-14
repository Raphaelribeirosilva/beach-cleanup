package com.raphael.beachcleanup.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@Profile("prod")
public class ProductionConfig {
    public ProductionConfig(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("test"))
                || !environment.acceptsProfiles(Profiles.of("mysql"))) {
            throw new IllegalStateException("O perfil prod exige mysql e não pode ser combinado com test.");
        }
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.startsWith("jdbc:mysql://")
                || !"com.mysql.cj.jdbc.Driver".equals(environment.getProperty("spring.datasource.driver-class-name"))
                || !"validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                || !environment.getProperty("spring.flyway.enabled", Boolean.class, false)
                || !environment.getProperty("spring.flyway.clean-disabled", Boolean.class, false)
                || environment.getProperty("spring.h2.console.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Configuração prod inválida: use MySQL, Flyway sem clean, "
                + "ddl-auto=validate e console H2 desabilitado.");
        }
    }
}
