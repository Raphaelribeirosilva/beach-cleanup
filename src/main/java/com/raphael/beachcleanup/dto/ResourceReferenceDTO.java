package com.raphael.beachcleanup.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ResourceReferenceDTO(
        @NotNull(message = "O ID do vínculo é obrigatório")
        @Positive(message = "O ID do vínculo deve ser positivo") Long id) {
}
