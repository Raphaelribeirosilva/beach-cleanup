package com.raphael.beachcleanup.dto;

import java.time.LocalDate;

import com.raphael.beachcleanup.entities.TrashCollection;

public record TrashCollectionDTO(Long id, String beach, String materialType, Double weightKg,
        LocalDate registrationDate, UserDTO volunteer, CleanupEventDTO event, Long version) {

    public TrashCollectionDTO(TrashCollection entity, UserDTO volunteer) {
        this(entity.getId(), entity.getBeach(), entity.getMaterialType(), entity.getWeightKg(),
            entity.getRegistrationDate(),
            volunteer,
            entity.getEvent() == null ? null : new CleanupEventDTO(entity.getEvent()), entity.getRowVersion());
    }
}
