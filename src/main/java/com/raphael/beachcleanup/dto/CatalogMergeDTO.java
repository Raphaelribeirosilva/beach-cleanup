package com.raphael.beachcleanup.dto;

import jakarta.validation.constraints.*;

public record CatalogMergeDTO(@NotNull @Positive Long targetId,
        @NotNull @PositiveOrZero Long version, @NotNull @PositiveOrZero Long targetVersion) {}
