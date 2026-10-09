package com.raphael.beachcleanup.dto;

import java.time.LocalDateTime;

import com.raphael.beachcleanup.entities.CleanupEvent;

public record CleanupEventDTO(Long id, String title, String location,
        LocalDateTime dateTime, String status) {

    public CleanupEventDTO(CleanupEvent entity) {
        this(entity.getId(), entity.getTitle(), entity.getLocation(),
            entity.getDateTime(), entity.getStatus());
    }
}
