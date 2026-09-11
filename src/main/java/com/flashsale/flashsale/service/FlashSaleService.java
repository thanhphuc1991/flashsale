package com.flashsale.flashsale.service;

import com.flashsale.common.exception.ApiException;
import com.flashsale.flashsale.domain.FlashSaleItem;
import com.flashsale.flashsale.domain.FlashSaleWindow;
import com.flashsale.flashsale.domain.OutboxEvent;
import com.flashsale.flashsale.domain.Purchase;
import com.flashsale.flashsale.dto.FlashSaleItemView;
import com.flashsale.flashsale.dto.PurchaseRequest;
import com.flashsale.flashsale.dto.PurchaseResponse;
import com.flashsale.flashsale.repository.FlashSaleItemRepository;
import com.flashsale.flashsale.repository.FlashSaleWindowRepository;
import com.flashsale.flashsale.repository.OutboxEventRepository;
import com.flashsale.flashsale.repository.PurchaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FlashSaleService {

    private final FlashSaleWindowRepository windowRepository;
    private final FlashSaleItemRepository itemRepository;
    private final PurchaseRepository purchaseRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public FlashSaleService(FlashSaleWindowRepository windowRepository,
                             FlashSaleItemRepository itemRepository,
                             PurchaseRepository purchaseRepository,
                             OutboxEventRepository outboxEventRepository,
                             ObjectMapper objectMapper) {
        this.windowRepository = windowRepository;
        this.itemRepository = itemRepository;
        this.purchaseRepository = purchaseRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<FlashSaleItemView> getCurrentItems() {
        Instant now = Instant.now();
        List<FlashSaleWindow> activeWindows = windowRepository.findActiveWindows(now);
        if (activeWindows.isEmpty()) return List.of();

        List<Long> windowIds = activeWindows.stream().map(FlashSaleWindow::getId).toList();
        return itemRepository.findByWindowIdIn(windowIds).stream()
                .map(i -> new FlashSaleItemView(
                        i.getId(), i.getWindowId(), i.getProductId(), i.getProductName(),
                        i.getPrice(), i.getRemaining()))
                .collect(Collectors.toList());
    }

    /**
     * Purchase flow — correctness under concurrency relies on THREE independent guarantees,
     * each enforced at the database level (not just in application code):
     *
     *  1. Stock never oversold: {@link FlashSaleItemRepository#tryReserveStock} is a single
     *     atomic "UPDATE ... WHERE quantity_sold < quantity_limit" — the database's row-locking
     *     during the UPDATE serializes concurrent buyers of the same item.
     *  2. One purchase per user per day: enforced by the UNIQUE INDEX on
     *     (user_id, purchase_date) in the `purchase` table. We still pre-check via
     *     the repository for a fast, friendly error, but the DB constraint is the
     *     real guarantee if two requests from the same user race each other.
     *  3. Idempotent retries: UNIQUE constraint on idempotency_key means a client
     *     retrying the same logical purchase (e.g. after a timeout) cannot create
     *     a duplicate purchase or double-decrement stock.
     *
     * If the purchase record can't be committed (day-limit or idempotency conflict)
     * after stock was already reserved, we compensate by releasing the reserved unit.
     */
    @Transactional
    public PurchaseResponse purchase(Long userId, PurchaseRequest request) {
        if (purchaseRepository.existsByIdempotencyKey(request.idempotencyKey())) {
            throw ApiException.conflict("DUPLICATE_REQUEST", "This purchase request was already processed");
        }

        FlashSaleItem item = itemRepository.findById(request.itemId())
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "Flash sale item not found"));

        FlashSaleWindow window = windowRepository.findById(item.getWindowId())
                .orElseThrow(() -> ApiException.notFound("WINDOW_NOT_FOUND", "Flash sale window not found"));

        Instant now = Instant.now();
        if (!window.isCurrentlyActive(now)) {
            throw ApiException.badRequest("WINDOW_NOT_ACTIVE", "This flash sale window is not currently active");
        }

        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (purchaseRepository.findByUserIdAndPurchaseDate(userId, today).isPresent()) {
            throw ApiException.conflict("DAILY_LIMIT_REACHED", "You already bought a flash sale item today");
        }

        int updatedRows = itemRepository.tryReserveStock(item.getId());
        if (updatedRows == 0) {
            throw ApiException.conflict("SOLD_OUT", "This item is sold out");
        }

        try {
            Purchase purchase = new Purchase(
                    userId, item.getId(), item.getWindowId(), today, item.getPrice(), request.idempotencyKey());
            purchase = purchaseRepository.save(purchase);

            publishPurchaseCompletedEvent(purchase, item);

            return new PurchaseResponse(purchase.getId(), item.getId(), item.getPrice(), "SUCCESS");
        } catch (RuntimeException ex) {
            // Compensate: the unique constraints above will normally throw BEFORE we get
            // here on a genuine race, but if persistence fails for any other reason we
            // must not leave stock permanently reserved for a purchase that never landed.
            itemRepository.releaseStock(item.getId());
            throw ApiException.conflict("PURCHASE_FAILED", "Could not complete purchase, please retry");
        }
    }

    private void publishPurchaseCompletedEvent(Purchase purchase, FlashSaleItem item) {
        try {
            Map<String, Object> payload = Map.of(
                    "eventId", java.util.UUID.randomUUID().toString(),
                    "purchaseId", purchase.getId(),
                    "productId", item.getProductId(),
                    "itemId", item.getId(),
                    "quantityDelta", -1,
                    "occurredAt", Instant.now().toString()
            );
            String json = objectMapper.writeValueAsString(payload);
            outboxEventRepository.save(new OutboxEvent(
                    "FLASH_SALE_ITEM", String.valueOf(item.getId()), "PURCHASE_COMPLETED", json));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event", e);
        }
    }
}
