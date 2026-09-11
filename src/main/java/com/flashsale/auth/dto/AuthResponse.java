package com.flashsale.auth.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds
) {}
