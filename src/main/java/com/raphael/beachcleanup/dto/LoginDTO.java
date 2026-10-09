package com.raphael.beachcleanup.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginDTO(
        @NotBlank(message = "O e-mail é obrigatório")
        @Email(message = "Formato de e-mail inválido")
        @Size(max = 255, message = "O e-mail deve ter no máximo 255 caracteres") String email,
        @NotBlank(message = "A senha é obrigatória")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
}
