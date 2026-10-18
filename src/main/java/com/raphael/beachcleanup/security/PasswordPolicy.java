package com.raphael.beachcleanup.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.raphael.beachcleanup.exceptions.FieldValidationException;

@Component
public class PasswordPolicy {
    private static final Set<String> COMMON = Set.of("123456789012345", "1234567890123456", "passwordpassword", "senha123456789012345", "qwertyuiopasdfgh");
    public void validate(String password, String field) {
        if (password == null || password.isBlank() || password.codePointCount(0, password.length()) < 15
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || password.codePoints().distinct().count() == 1 || COMMON.contains(password.toLowerCase(Locale.ROOT)))
            throw new FieldValidationException(field, "Use pelo menos 15 caracteres, no máximo 72 bytes UTF-8 e evite senhas comuns ou um único caractere repetido.");
    }
}
