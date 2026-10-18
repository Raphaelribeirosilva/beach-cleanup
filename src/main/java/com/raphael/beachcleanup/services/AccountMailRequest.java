package com.raphael.beachcleanup.services;
public record AccountMailRequest(String email, String purpose) {
    @Override public String toString() { return "AccountMailRequest[REDACTED]"; }
}
