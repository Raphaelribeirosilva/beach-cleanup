package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:h2consoletests",
    "spring.jpa.show-sql=false"
})
class H2ConsoleIntegrationTests {
    @LocalServerPort int port;

    private final HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Test
    void consoleLoginPageIsAccessibleWithoutTokenAndAllowsSameOriginFrames() throws Exception {
        var response = get("/h2-console/");
        assertEquals(200, response.statusCode());
        var loginPath = Pattern.compile("login\\.jsp\\?jsessionid=[a-z0-9]+").matcher(response.body());
        assertTrue(loginPath.find());
        var loginPage = get("/h2-console/" + loginPath.group());
        assertEquals(200, loginPage.statusCode());
        assertTrue(loginPage.body().contains("name=\"url\""));
        assertEquals("SAMEORIGIN", response.headers().firstValue("X-Frame-Options").orElseThrow());
        assertEquals("SAMEORIGIN", loginPage.headers().firstValue("X-Frame-Options").orElseThrow());

        var login = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/h2-console/" + loginPath.group().replace("login.jsp", "login.do")))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(
                "driver=org.h2.Driver&url=jdbc%3Ah2%3Amem%3Ah2consoletests&user=sa&password="))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        assertTrue(login.body().contains("h2query"), login.body());
    }

    @Test
    void apiStillRequiresAuthenticationAndDeniesFrames() throws Exception {
        var response = get("/users");
        assertEquals(401, response.statusCode());
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElseThrow());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
