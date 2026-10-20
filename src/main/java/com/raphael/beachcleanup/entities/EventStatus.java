package com.raphael.beachcleanup.entities;

import com.raphael.beachcleanup.exceptions.FieldValidationException;

public enum EventStatus {
    SCHEDULED("Agendado"), IN_PROGRESS("Em andamento"), PAUSED("Pausado"),
    COMPLETED("Concluido"), CANCELLED("Cancelado");

    private final String value;

    EventStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean acceptsNewRecords() {
        return this == SCHEDULED || this == IN_PROGRESS;
    }

    public boolean canTransitionTo(EventStatus next) {
        if (this == next) {
            return true;
        }
        return switch (this) {
            case SCHEDULED -> next == IN_PROGRESS || next == PAUSED || next == CANCELLED;
            case IN_PROGRESS -> next == PAUSED || next == COMPLETED || next == CANCELLED;
            case PAUSED -> next == SCHEDULED || next == IN_PROGRESS || next == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    public static EventStatus parse(String input) {
        String normalized = input == null ? "" : input.strip();
        if (normalized.equalsIgnoreCase("Concluído") || normalized.equalsIgnoreCase("Finalizado")) {
            return COMPLETED;
        }
        for (var status : values()) {
            if (status.value.equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        throw new FieldValidationException("status",
            "Use Agendado, Em andamento, Pausado, Finalizado (Concluido) ou Cancelado.");
    }
}
