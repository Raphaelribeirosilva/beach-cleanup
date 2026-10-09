package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.raphael.beachcleanup.config.JwtConfig;

class JwtConfigTests {
    private final JwtConfig config = new JwtConfig();

    @Test
    void environmentsOutsideTestRequireAValidSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        for (String secret : new String[] {"", "base64-invalido!", Base64.getEncoder().encodeToString(new byte[31])}) {
            assertThrows(IllegalStateException.class, () -> config.jwtSigningKey(secret, environment));
        }
    }

    @Test
    void suppliedSecretIsPreservedAcrossInstances() {
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        String encoded = Base64.getEncoder().encodeToString(secret);
        assertArrayEquals(secret, config.jwtSigningKey(encoded, new MockEnvironment()).getEncoded());
        assertArrayEquals(secret, new JwtConfig().jwtSigningKey(encoded, new MockEnvironment()).getEncoded());
    }

    @Test
    void testEnvironmentGeneratesAnEphemeralKeyInsteadOfUsingACommittedSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        byte[] first = config.jwtSigningKey("", environment).getEncoded();
        byte[] second = config.jwtSigningKey("", environment).getEncoded();
        assertEquals(32, first.length);
        assertFalse(java.util.Arrays.equals(first, second));
    }
}
