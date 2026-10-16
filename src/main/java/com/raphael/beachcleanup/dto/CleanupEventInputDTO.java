package com.raphael.beachcleanup.dto;

import java.time.LocalDateTime;
import com.raphael.beachcleanup.entities.CleanupEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PositiveOrZero;

public record CleanupEventInputDTO(
        @NotBlank(message = "O título é obrigatório")
        @Size(max = 255, message = "O título deve ter no máximo 255 caracteres") String title,
        @NotBlank(message = "O local é obrigatório")
        @Size(max = 255, message = "O local deve ter no máximo 255 caracteres") String location,
        @NotNull(message = "A data e hora são obrigatórias") LocalDateTime dateTime,
        @NotBlank(message = "O status é obrigatório")
        @Size(max = 255, message = "O status deve ter no máximo 255 caracteres") String status,
        @PositiveOrZero(message = "A versão deve ser zero ou positiva") Long version) {

    public CleanupEvent toEntity() {
        return new CleanupEvent(null, title, location, dateTime, status);
    }
}
