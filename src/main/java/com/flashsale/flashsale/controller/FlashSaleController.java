package com.flashsale.flashsale.controller;

import com.flashsale.flashsale.dto.FlashSaleItemView;
import com.flashsale.flashsale.dto.PurchaseRequest;
import com.flashsale.flashsale.dto.PurchaseResponse;
import com.flashsale.flashsale.service.FlashSaleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/flash-sale")
public class FlashSaleController {

    private final FlashSaleService flashSaleService;

    public FlashSaleController(FlashSaleService flashSaleService) {
        this.flashSaleService = flashSaleService;
    }

    /** Public: list products currently on flash sale right now. */
    @GetMapping("/current")
    public ResponseEntity<List<FlashSaleItemView>> current() {
        return ResponseEntity.ok(flashSaleService.getCurrentItems());
    }

    /** Authenticated: purchase a flash-sale item. userId comes from the JWT (see JwtAuthFilter). */
    @PostMapping("/purchase")
    public ResponseEntity<PurchaseResponse> purchase(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody PurchaseRequest request) {
        return ResponseEntity.ok(flashSaleService.purchase(userId, request));
    }
}
