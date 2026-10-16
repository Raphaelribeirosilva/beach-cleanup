package com.raphael.beachcleanup.dto;

import java.time.LocalDateTime;

import com.raphael.beachcleanup.entities.CleanupEvent;

public record CleanupEventDTO(Long id, String title, String location,
        LocalDateTime dateTime, String status, Long version, Long beachId) {

    public CleanupEventDTO(CleanupEvent entity) {
        this(entity.getId(), entity.getTitle(), entity.getBeachReference() == null ? entity.getLocation() : entity.getBeachReference().getName(),
            entity.getDateTime(), entity.getStatus(), entity.getRowVersion(),
            entity.getBeachReference() == null ? null : entity.getBeachReference().getId());
    }
}
