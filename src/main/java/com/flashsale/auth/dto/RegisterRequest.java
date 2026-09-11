package com.flashsale.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank String identifier, // email or phone; type is auto-detected
        @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password
) {}
