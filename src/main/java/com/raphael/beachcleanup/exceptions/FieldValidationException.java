package com.raphael.beachcleanup.exceptions;

public class FieldValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String fieldName;

    public FieldValidationException(String fieldName, String message) {
        super(message);
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }
}
