package com.raphael.beachcleanup.dto;

import java.time.LocalDate;

import com.raphael.beachcleanup.entities.TrashCollection;

public record TrashCollectionDTO(Long id, String beach, String materialType, Double weithKg,
        LocalDate regitrationDate, VolunteerDTO volunteer, CleanupEventDTO event) {

    public TrashCollectionDTO(TrashCollection entity) {
        this(entity.getId(), entity.getBeach(), entity.getMaterialType(), entity.getWeithKg(),
            entity.getRegitrationDate(),
            entity.getVolunteer() == null ? null : new VolunteerDTO(entity.getVolunteer()),
            entity.getEvent() == null ? null : new CleanupEventDTO(entity.getEvent()));
    }
}
