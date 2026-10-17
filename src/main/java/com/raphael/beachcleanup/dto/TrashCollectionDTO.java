package com.raphael.beachcleanup.dto;

import java.time.LocalDate;

import com.raphael.beachcleanup.entities.TrashCollection;

public record TrashCollectionDTO(Long id, String beach, String materialType, java.math.BigDecimal weightKg,
        LocalDate registrationDate, UserDTO volunteer, CleanupEventDTO event, Long version, Long beachId, Long materialId) {

    public TrashCollectionDTO(TrashCollection entity, UserDTO volunteer) {
        this(entity.getId(), entity.getBeachReference() == null ? entity.getBeach() : entity.getBeachReference().getName(),
            entity.getMaterialReference() == null ? entity.getMaterialType() : entity.getMaterialReference().getName(), entity.getWeightKg(),
            entity.getRegistrationDate(),
            volunteer,
            entity.getEvent() == null ? null : new CleanupEventDTO(entity.getEvent()), entity.getRowVersion(),
            entity.getBeachReference() == null ? null : entity.getBeachReference().getId(),
            entity.getMaterialReference() == null ? null : entity.getMaterialReference().getId());
    }
}
