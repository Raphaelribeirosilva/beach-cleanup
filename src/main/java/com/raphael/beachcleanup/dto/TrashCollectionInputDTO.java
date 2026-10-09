package com.raphael.beachcleanup.dto;

import java.time.LocalDate;
import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.entities.Volunteer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TrashCollectionInputDTO(
        @NotBlank(message = "A praia é obrigatória")
        @Size(max = 255, message = "A praia deve ter no máximo 255 caracteres") String beach,
        @NotBlank(message = "O material é obrigatório")
        @Size(max = 255, message = "O material deve ter no máximo 255 caracteres") String materialType,
        @NotNull(message = "O peso é obrigatório")
        @Positive(message = "O peso deve ser maior que zero")
        @DecimalMax(value = "1.7976931348623157E308", message = "O peso deve ser finito") Double weithKg,
        @NotNull(message = "A data de registro é obrigatória") LocalDate regitrationDate,
        @Valid ResourceReferenceDTO volunteer,
        @Valid ResourceReferenceDTO event) {

    public TrashCollection toEntity() {
        TrashCollection entity = new TrashCollection(null, beach, materialType, weithKg, regitrationDate);
        if (volunteer != null) {
            Volunteer reference = new Volunteer();
            reference.setId(volunteer.id());
            entity.setVolunteer(reference);
        }
        if (event != null) {
            CleanupEvent reference = new CleanupEvent();
            reference.setId(event.id());
            entity.setEvent(reference);
        }
        return entity;
    }
}
