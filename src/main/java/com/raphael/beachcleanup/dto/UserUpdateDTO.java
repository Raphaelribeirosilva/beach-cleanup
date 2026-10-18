package com.raphael.beachcleanup.dto;

import com.raphael.beachcleanup.entities.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PositiveOrZero;

public record UserUpdateDTO(
        @NotBlank(message = "O nome é obrigatório")
        @Size(max = 255, message = "O nome deve ter no máximo 255 caracteres") String name,
        @NotBlank(message = "O e-mail é obrigatório")
        @Email(message = "Formato de e-mail inválido")
        @Size(max = 255, message = "O e-mail deve ter no máximo 255 caracteres") String email,
        @PositiveOrZero(message = "A versão deve ser zero ou positiva") Long version,
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String currentPassword) {

    @Override public String toString() { return "UserUpdateDTO[REDACTED]"; }

    public User toEntity() {
        return new User(null, name, email, null, null);
    }
}
