package com.raphael.beachcleanup.dto;

public record TokenDTO(String accessToken, String tokenType, long expiresIn, Long userId, String role,
        String refreshToken, long refreshExpiresIn) {
    @Override public String toString() { return "TokenDTO[userId=" + userId + ", tokens=REDACTED]"; }
}
