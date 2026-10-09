package com.raphael.beachcleanup.dto;

public record TokenDTO(String accessToken, String tokenType, long expiresIn, Long userId, String role) {
}
