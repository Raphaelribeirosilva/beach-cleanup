package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.services.CollectionDataRules;
import com.raphael.beachcleanup.exceptions.FieldValidationException;

class CollectionDataRulesTests {
    @Test
    void saoPauloDayBoundaryIsIndependentOfMachineTimezoneAndServicesRejectExcessPrecision() {
        var rules = new CollectionDataRules(Clock.fixed(Instant.parse("2026-10-18T01:30:00Z"), ZoneOffset.UTC));
        assertEquals(LocalDate.of(2026, 10, 17), rules.today());
        var collection = new TrashCollection(null, "Praia", "Vidro", new BigDecimal("0.001"), rules.today());
        rules.validate(collection);
        assertEquals(new BigDecimal("0.001"), collection.getWeightKg());
        collection.setRegistrationDate(LocalDate.of(2026, 10, 18));
        assertThrows(FieldValidationException.class, () -> rules.validate(collection));
        collection.setRegistrationDate(CollectionDataRules.FIRST_DATE);
        collection.setWeightKg(new BigDecimal("1.2345"));
        assertThrows(FieldValidationException.class, () -> rules.validate(collection));
        collection.setWeightKg(new BigDecimal("1.2300"));
        rules.validate(collection);
        assertEquals(new BigDecimal("1.230"), collection.getWeightKg());
    }
}
