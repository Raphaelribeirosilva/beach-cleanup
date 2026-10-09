package com.raphael.beachcleanup.dto;

import java.time.LocalDate;

import com.raphael.beachcleanup.entities.TrashCollection;

public record TrashCollectionDTO(Long id, String beach, String materialType, Double weightKg,
        LocalDate registrationDate, UserDTO volunteer, CleanupEventDTO event) {

    public TrashCollectionDTO(TrashCollection entity) {
        this(entity.getId(), entity.getBeach(), entity.getMaterialType(), entity.getWeightKg(),
            entity.getRegistrationDate(),
            entity.getVolunteer() == null ? null : new UserDTO(entity.getVolunteer()),
            entity.getEvent() == null ? null : new CleanupEventDTO(entity.getEvent()));
    }
}
