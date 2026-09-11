package com.flashsale.flashsale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.exception.ApiException;
import com.flashsale.flashsale.domain.FlashSaleItem;
import com.flashsale.flashsale.domain.FlashSaleWindow;
import com.flashsale.flashsale.domain.Purchase;
import com.flashsale.flashsale.dto.PurchaseRequest;
import com.flashsale.flashsale.repository.FlashSaleItemRepository;
import com.flashsale.flashsale.repository.FlashSaleWindowRepository;
import com.flashsale.flashsale.repository.OutboxEventRepository;
import com.flashsale.flashsale.repository.PurchaseRepository;
import com.flashsale.flashsale.service.FlashSaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit-level coverage of the flash-sale business rules from the spec
 * (concurrency itself is proven separately by FlashSaleConcurrencyTest
 * against a real (H2 in-memory) database):
 *  - sold out is rejected when the atomic reservation returns 0 rows
 *  - purchases outside the active window are rejected
 *  - a second purchase the same day is rejected
 *  - a retried request (same idempotency key) is rejected without re-selling
 */
class FlashSaleServiceTest {

    private FlashSaleWindowRepository windowRepository;
    private FlashSaleItemRepository itemRepository;
    private PurchaseRepository purchaseRepository;
    private OutboxEventRepository outboxEventRepository;
    private FlashSaleService flashSaleService;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        windowRepository = mock(FlashSaleWindowRepository.class);
        itemRepository = mock(FlashSaleItemRepository.class);
        purchaseRepository = mock(PurchaseRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        flashSaleService = new FlashSaleService(
                windowRepository, itemRepository, purchaseRepository, outboxEventRepository, new ObjectMapper());
    }

    @Test
    void purchaseSucceedsWhenStockAvailableAndWindowActive() throws Exception {
        FlashSaleItem item = buildItem(10L, 100L, 501L, new BigDecimal("49.99"));
        FlashSaleWindow window = activeWindow();
        mockHappyPathLookups(item, window);
        when(itemRepository.tryReserveStock(10L)).thenReturn(1); // reservation succeeds
        // Real JPA (IDENTITY generation) assigns the id synchronously on save();
        // mimic that here so downstream code that reads purchase.getId() works as in production.
        when(purchaseRepository.save(any(Purchase.class))).thenAnswer(inv -> {
            Purchase saved = inv.getArgument(0);
            setField(saved, "id", 999L);
            return saved;
        });

        var response = flashSaleService.purchase(USER_ID, new PurchaseRequest(10L, "idem-key-1"));

        assertEquals("SUCCESS", response.status());
        assertEquals(10L, response.itemId());
        verify(outboxEventRepository).save(any());
    }

    @Test
    void purchaseRejectedWhenSoldOut() throws Exception {
        FlashSaleItem item = buildItem(10L, 100L, 501L, new BigDecimal("49.99"));
        FlashSaleWindow window = activeWindow();
        mockHappyPathLookups(item, window);
        when(itemRepository.tryReserveStock(10L)).thenReturn(0); // no rows updated -> sold out

        ApiException ex = assertThrows(ApiException.class,
                () -> flashSaleService.purchase(USER_ID, new PurchaseRequest(10L, "idem-key-2")));

        assertEquals("SOLD_OUT", ex.getErrorCode());
        verify(purchaseRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void purchaseRejectedWhenWindowNotYetStarted() throws Exception {
        FlashSaleItem item = buildItem(10L, 100L, 501L, new BigDecimal("49.99"));
        FlashSaleWindow window = buildWindow(Instant.now().plus(1, ChronoUnit.HOURS),
                Instant.now().plus(2, ChronoUnit.HOURS));
        when(purchaseRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));
        when(windowRepository.findById(100L)).thenReturn(Optional.of(window));

        ApiException ex = assertThrows(ApiException.class,
                () -> flashSaleService.purchase(USER_ID, new PurchaseRequest(10L, "idem-key-3")));

        assertEquals("WINDOW_NOT_ACTIVE", ex.getErrorCode());
        verify(itemRepository, never()).tryReserveStock(any());
    }

    @Test
    void purchaseRejectedWhenWindowAlreadyEnded() throws Exception {
        FlashSaleItem item = buildItem(10L, 100L, 501L, new BigDecimal("49.99"));
        FlashSaleWindow window = buildWindow(Instant.now().minus(2, ChronoUnit.HOURS),
                Instant.now().minus(1, ChronoUnit.HOURS));
        when(purchaseRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));
        when(windowRepository.findById(100L)).thenReturn(Optional.of(window));

        ApiException ex = assertThrows(ApiException.class,
                () -> flashSaleService.purchase(USER_ID, new PurchaseRequest(10L, "idem-key-4")));

        assertEquals("WINDOW_NOT_ACTIVE", ex.getErrorCode());
    }

    @Test
    void purchaseRejectedWhenUserAlreadyBoughtToday() throws Exception {
        FlashSaleItem item = buildItem(10L, 100L, 501L, new BigDecimal("49.99"));
        FlashSaleWindow window = activeWindow();
        when(purchaseRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));
        when(windowRepository.findById(100L)).thenReturn(Optional.of(window));
        when(purchaseRepository.findByUserIdAndPurchaseDate(eq(USER_ID), any(LocalDate.class)))
                .thenReturn(Optional.of(mock(Purchase.class)));

        ApiException ex = assertThrows(ApiException.class,
                () -> flashSaleService.purchase(USER_ID, new PurchaseRequest(10L, "idem-key-5")));

        assertEquals("DAILY_LIMIT_REACHED", ex.getErrorCode());
        verify(itemRepository, never()).tryReserveStock(any());
    }

    @Test
    void purchaseRejectedOnRetriedIdempotencyKeyWithoutTouchingStock() {
        when(purchaseRepository.existsByIdempotencyKey("already-used-key")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
                () -> flashSaleService.purchase(USER_ID, new PurchaseRequest(10L, "already-used-key")));

        assertEquals("DUPLICATE_REQUEST", ex.getErrorCode());
        verifyNoInteractions(itemRepository);
    }

    // --- fixtures via reflection (entities only expose protected no-arg ctors + JPA-managed ids) ---

    private void mockHappyPathLookups(FlashSaleItem item, FlashSaleWindow window) {
        when(purchaseRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(windowRepository.findById(item.getWindowId())).thenReturn(Optional.of(window));
        when(purchaseRepository.findByUserIdAndPurchaseDate(eq(USER_ID), any(LocalDate.class)))
                .thenReturn(Optional.empty());
    }

    private FlashSaleWindow activeWindow() throws Exception {
        return buildWindow(Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));
    }

    private FlashSaleWindow buildWindow(Instant start, Instant end) {
        return new FlashSaleWindow("test-window", start, end);
    }

    private FlashSaleItem buildItem(Long id, Long windowId, Long productId, BigDecimal price) throws Exception {
        var ctor = FlashSaleItem.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        FlashSaleItem item = ctor.newInstance();
        setField(item, "id", id);
        setField(item, "windowId", windowId);
        setField(item, "productId", productId);
        setField(item, "productName", "Test Product");
        setField(item, "price", price);
        setField(item, "quantityLimit", 10);
        setField(item, "quantitySold", 0);
        return item;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
