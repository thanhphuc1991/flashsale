package com.flashsale.flashsale.dto;

import java.math.BigDecimal;

public record PurchaseResponse(
        Long purchaseId,
        Long itemId,
        BigDecimal pricePaid,
        String status
) {}
