package com.flashsale.flashsale.dto;

import java.math.BigDecimal;

public record FlashSaleItemView(
        Long itemId,
        Long windowId,
        Long productId,
        String productName,
        BigDecimal price,
        int remaining
) {}
