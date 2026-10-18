package com.raphael.beachcleanup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import com.fasterxml.jackson.annotation.JsonProperty;

public record RefreshTokenDTO(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "Refresh token inválido")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String refreshToken) {
    @Override public String toString() { return "RefreshTokenDTO[REDACTED]"; }
}
