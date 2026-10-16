package com.raphael.beachcleanup.services;

import com.raphael.beachcleanup.exceptions.FieldValidationException;
import org.springframework.dao.OptimisticLockingFailureException;

final class FormVersion {
    private FormVersion() {}

    static void requireCurrent(Long supplied, Long current) {
        if (supplied == null || supplied < 0) {
            throw new FieldValidationException("version", "Informe a versão obtida ao carregar o registro.");
        }
        if (!supplied.equals(current)) {
            throw new OptimisticLockingFailureException("Stale form version");
        }
    }
}
