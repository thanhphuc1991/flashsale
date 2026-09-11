package com.flashsale.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.flashsale.domain.OutboxEvent;
import com.flashsale.flashsale.repository.OutboxEventRepository;
import com.flashsale.inventory.service.InventoryService;
import com.flashsale.inventory.service.InventorySyncProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Covers the in-process outbox -> inventory sync (replaces what used to be a
 * Kafka consumer test): pending events get applied exactly once and marked
 * published; a bad payload is marked failed instead of crashing the batch.
 */
class InventorySyncProcessorTest {

    private OutboxEventRepository outboxEventRepository;
    private InventoryService inventoryService;
    private InventorySyncProcessor processor;

    @BeforeEach
    void setUp() {
        outboxEventRepository = mock(OutboxEventRepository.class);
        inventoryService = mock(InventoryService.class);
        processor = new InventorySyncProcessor(
                outboxEventRepository, inventoryService, new ObjectMapper(), 100);
    }

    @Test
    void appliesPendingEventsAndMarksThemPublished() throws Exception {
        OutboxEvent event = buildEvent(42L, "{\"productId\":501,\"quantityDelta\":-1}");
        when(outboxEventRepository.findPendingBatch(any())).thenReturn(List.of(event));
        when(inventoryService.applyOnce("outbox-42", 501L, -1)).thenReturn(true);

        processor.processPending();

        verify(inventoryService).applyOnce("outbox-42", 501L, -1);
        assertEquals("PUBLISHED", event.getStatus());
    }

    @Test
    void malformedPayloadIsMarkedFailedNotThrown() throws Exception {
        OutboxEvent event = buildEvent(43L, "not-valid-json");
        when(outboxEventRepository.findPendingBatch(any())).thenReturn(List.of(event));

        processor.processPending(); // must not throw

        verify(inventoryService, never()).applyOnce(any(), any(), anyInt());
        assertEquals("FAILED", event.getStatus());
    }

    private OutboxEvent buildEvent(Long id, String payload) throws Exception {
        OutboxEvent event = new OutboxEvent("FLASH_SALE_ITEM", "1", "PURCHASE_COMPLETED", payload);
        Field idField = OutboxEvent.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(event, id);
        return event;
    }
}
