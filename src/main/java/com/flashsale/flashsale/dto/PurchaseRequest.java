package com.flashsale.flashsale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PurchaseRequest(
        @NotNull Long itemId,
        // Client-generated idempotency key: protects against double-submit on
        // network retry (e.g. user double-taps "Buy" or the client times out and retries).
        @NotBlank String idempotencyKey
) {}
