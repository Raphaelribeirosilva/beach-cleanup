package com.raphael.beachcleanup.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordDTO(
        @NotBlank(message = "A senha atual é obrigatória")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String currentPassword,
        @NotBlank(message = "A nova senha é obrigatória")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String newPassword) {
}
