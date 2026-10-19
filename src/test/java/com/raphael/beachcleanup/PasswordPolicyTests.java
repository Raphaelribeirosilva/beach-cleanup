package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.raphael.beachcleanup.exceptions.FieldValidationException;
import com.raphael.beachcleanup.security.PasswordPolicy;

class PasswordPolicyTests {
    @Test
    void countsUnicodeCharactersAndKeepsTheUtf8ByteLimit() {
        var policy = new PasswordPolicy();
        assertDoesNotThrow(() -> policy.validate("Praia7!", "password"));
        assertDoesNotThrow(() -> policy.validate("🌊Sol7!a", "password"));
        assertThrows(FieldValidationException.class, () -> policy.validate("🌊Sol7!", "password"));
        assertThrows(FieldValidationException.class, () -> policy.validate("Mar7!a", "password"));
        assertThrows(FieldValidationException.class, () -> policy.validate("🌊".repeat(18) + "a", "password"));
    }
}
