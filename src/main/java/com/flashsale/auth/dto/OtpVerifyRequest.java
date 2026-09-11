package com.flashsale.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record OtpVerifyRequest(
        @NotBlank String identifier,
        @NotBlank String otpCode
) {}
