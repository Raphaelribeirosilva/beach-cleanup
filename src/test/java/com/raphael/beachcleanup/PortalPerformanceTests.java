package com.raphael.beachcleanup;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:portalvolume", "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.generate_statistics=true", "logging.level.org.hibernate.stat=OFF"})
class PortalPerformanceTests {
    @Autowired org.springframework.context.ApplicationContext context;
    @Test void measureRepresentativeVolume() throws Exception {
        PortalPerformanceProbe.run(context,System.getProperty("portal.benchmark.label","current"),
            "H2 in-memory (not production MySQL latency)");
    }
}
