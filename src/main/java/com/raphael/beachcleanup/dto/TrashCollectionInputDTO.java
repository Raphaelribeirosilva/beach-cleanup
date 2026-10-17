package com.raphael.beachcleanup.dto;

import java.time.LocalDate;
import java.math.BigDecimal;
import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.entities.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PositiveOrZero;

public record TrashCollectionInputDTO(
        @Size(max = 255, message = "A praia deve ter no máximo 255 caracteres") String beach,
        @Size(max = 255, message = "O material deve ter no máximo 255 caracteres") String materialType,
        @NotNull(message = "O peso é obrigatório")
        @DecimalMin(value = "0.001", message = "O peso mínimo é 0,001 kg")
        @DecimalMax(value = "100000.000", message = "O peso máximo é 100.000 kg") BigDecimal weightKg,
        @NotNull(message = "A data de registro é obrigatória") LocalDate registrationDate,
        @Valid ResourceReferenceDTO volunteer,
        @Valid ResourceReferenceDTO event,
        @PositiveOrZero(message = "A versão deve ser zero ou positiva") Long version,
        @jakarta.validation.constraints.Positive Long beachId,
        @jakarta.validation.constraints.Positive Long materialId) {

    public TrashCollection toEntity() {
        TrashCollection entity = new TrashCollection(null, beach, materialType, weightKg, registrationDate);
        if (beachId != null) entity.setBeachReference(com.raphael.beachcleanup.entities.CatalogEntry.reference(beachId));
        if (materialId != null) entity.setMaterialReference(com.raphael.beachcleanup.entities.CatalogEntry.reference(materialId));
        if (volunteer != null) {
            User reference = new User();
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
