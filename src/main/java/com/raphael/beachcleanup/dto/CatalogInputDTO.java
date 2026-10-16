package com.raphael.beachcleanup.dto;

import java.util.List;
import jakarta.validation.constraints.*;

public record CatalogInputDTO(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 50) List<@NotBlank @Size(max = 255) String> aliases,
        @PositiveOrZero Long version) {}
