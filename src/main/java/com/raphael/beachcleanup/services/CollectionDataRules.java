package com.raphael.beachcleanup.services;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.exceptions.FieldValidationException;

/** Domain rules also apply to callers that bypass HTTP/Bean Validation. */
@Component
public class CollectionDataRules {
    public static final BigDecimal MIN_WEIGHT = new BigDecimal("0.001");
    public static final BigDecimal MAX_WEIGHT = new BigDecimal("100000.000");
    public static final LocalDate FIRST_DATE = LocalDate.of(1900, 1, 1);
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private final Clock clock;

    public CollectionDataRules() { this(Clock.system(ZONE)); }
    public CollectionDataRules(Clock clock) { this.clock = clock; }
    public LocalDate today() { return LocalDate.now(clock.withZone(ZONE)); }

    public void validate(TrashCollection collection) {
        var weight = collection.getWeightKg();
        if (weight == null || weight.compareTo(MIN_WEIGHT) < 0 || weight.compareTo(MAX_WEIGHT) > 0
                || weight.stripTrailingZeros().scale() > 3) {
            throw new FieldValidationException("weightKg", "Informe de 0,001 a 100.000 kg, com até 3 casas decimais.");
        }
        collection.setWeightKg(weight.setScale(3));
        LocalDate date = collection.getRegistrationDate();
        if (date == null || date.isBefore(FIRST_DATE) || date.isAfter(today())) {
            throw new FieldValidationException("registrationDate", "A coleta deve ter data entre 01/01/1900 e hoje (São Paulo).");
        }
        var event = collection.getEvent();
        if (event != null) {
            if (event.getBeachReference() == null || collection.getBeachReference() == null
                    || !event.getBeachReference().getId().equals(collection.getBeachReference().getId())) {
                throw new FieldValidationException("beachId", "A praia da coleta deve ser a mesma do evento. Revise o evento ou remova o vínculo.");
            }
            if (event.getDateTime() == null || !date.equals(event.getDateTime().toLocalDate())) {
                throw new FieldValidationException("registrationDate", "A data da coleta deve ser a mesma data do evento.");
            }
        }
    }
}
