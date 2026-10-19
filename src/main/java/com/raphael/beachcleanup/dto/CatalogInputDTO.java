package com.raphael.beachcleanup.dto;

import java.util.List;
import jakarta.validation.constraints.*;

public record CatalogInputDTO(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 50) List<@NotBlank @Size(max = 255) String> aliases,
        @PositiveOrZero Long version,
        @Pattern(regexp = "[A-Z]{2}") String state,
        @Size(max = 255) String city) {
    public CatalogInputDTO(String name, List<String> aliases, Long version) {
        this(name, aliases, version, null, null);
    }
}
