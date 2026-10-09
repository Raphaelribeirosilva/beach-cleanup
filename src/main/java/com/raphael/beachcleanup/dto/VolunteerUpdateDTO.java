package com.raphael.beachcleanup.dto;

import com.raphael.beachcleanup.entities.Volunteer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VolunteerUpdateDTO(
        @NotBlank(message = "O nome é obrigatório")
        @Size(max = 255, message = "O nome deve ter no máximo 255 caracteres") String name,
        @NotBlank(message = "O e-mail é obrigatório")
        @Email(message = "Formato de e-mail inválido")
        @Size(max = 255, message = "O e-mail deve ter no máximo 255 caracteres") String email) {

    public Volunteer toEntity() {
        return new Volunteer(null, name, email, null, null);
    }
}
