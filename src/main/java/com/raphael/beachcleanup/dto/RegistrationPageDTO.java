package com.raphael.beachcleanup.dto;

import java.util.List;

/** Counts cover all registrations; content and requested membership IDs stay bounded. */
public record RegistrationPageDTO(List<CleanupEventDTO> content, int number, int size,
        long totalElements, int totalPages, List<Long> registeredEventIds) {}
