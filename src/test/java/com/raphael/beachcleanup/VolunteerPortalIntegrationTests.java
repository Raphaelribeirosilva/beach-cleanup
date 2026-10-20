package com.raphael.beachcleanup;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:portaltests", "spring.jpa.show-sql=false"})
class VolunteerPortalIntegrationTests {
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void publicPortalAndAssetsAreServedWithRestrictiveContentPolicy() throws Exception {
        // Spring's welcome mapping forwards the root to the static index.
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
            .andExpect(content().string(containsString("lang=\"pt-BR\"")))
            .andExpect(content().string(containsString("id=\"collection-form\"")))
            .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")));
        for (String asset : new String[] {"/portal.css", "/portal.js", "/portal-api.js", "/portal-admin.js", "/portal-ui.js", "/portal-reports.js", "/portal-admin.css", "/portal-account.css"}) {
            mvc.perform(get(asset)).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
    }

    @Test
    void optimizedPhotosArePublicReadOnlyAndOriginalsStayPrivate() throws Exception {
        for (int width : new int[] {480, 960, 1440}) {
            String path = "/images/web/praia-de-camburi-" + width + ".jpg";
            mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
            mvc.perform(head(path)).andExpect(status().isOk());
            mvc.perform(post(path)).andExpect(status().isForbidden());
            mvc.perform(delete(path).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
        }
        mvc.perform(get("/images/praia-de-camburi.jpg")).andExpect(status().isUnauthorized());
    }

    @Test
    void openingThePortalDoesNotExposePrivateApiOrOtherStaticPaths() throws Exception {
        for (String path : new String[] {"/users", "/users/me/events", "/trashCollection/search",
                "/unlisted-file.js"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/cleanupEvent/search")).andExpect(status().isOk());
        mvc.perform(post("/portal.js")).andExpect(status().isForbidden());
        mvc.perform(post("/portal-admin.js")).andExpect(status().isForbidden());
    }
}
