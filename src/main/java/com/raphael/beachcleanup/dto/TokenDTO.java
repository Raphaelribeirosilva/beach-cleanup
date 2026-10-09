package com.raphael.beachcleanup.dto;

public record TokenDTO(String accessToken, String tokenType, long expiresIn, Long volunteerId, String role) {
}
