package com.raphael.beachcleanup.dto;

import java.util.List;

public record CatalogDTO(Long id, String name, Long version, List<String> aliases) {}
