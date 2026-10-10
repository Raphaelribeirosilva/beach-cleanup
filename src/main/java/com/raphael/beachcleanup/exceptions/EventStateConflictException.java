package com.raphael.beachcleanup.exceptions;

public class EventStateConflictException extends RuntimeException {
    public EventStateConflictException(String message) {
        super(message);
    }
}
