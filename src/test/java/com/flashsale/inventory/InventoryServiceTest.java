package com.flashsale.inventory;

import com.flashsale.inventory.domain.Inventory;
import com.flashsale.inventory.domain.ProcessedEvent;
import com.flashsale.inventory.repository.InventoryRepository;
import com.flashsale.inventory.repository.ProcessedEventRepository;
import com.flashsale.inventory.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Proves the "no duplicate processing" requirement for tồn kho sync:
 * redelivering the same outbox event (e.g. after an app restart mid-batch) must not
 * decrement inventory twice.
 */
class InventoryServiceTest {

    private InventoryRepository inventoryRepository;
    private ProcessedEventRepository processedEventRepository;
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryRepository = mock(InventoryRepository.class);
        processedEventRepository = mock(ProcessedEventRepository.class);
        inventoryService = new InventoryService(inventoryRepository, processedEventRepository);
    }

    @Test
    void firstDeliveryOfEventAppliesInventoryDelta() {
        when(processedEventRepository.save(any(ProcessedEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(inventoryRepository.findById(501L)).thenReturn(Optional.empty());

        boolean applied = inventoryService.applyOnce("event-1", 501L, -1);

        assertTrue(applied);
        verify(inventoryRepository).save(any(Inventory.class));
    }

    @Test
    void duplicateDeliveryOfSameEventIsSkipped() {
        when(processedEventRepository.save(any(ProcessedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key: event-1"));

        boolean applied = inventoryService.applyOnce("event-1", 501L, -1);

        assertFalse(applied);
        verify(inventoryRepository, never()).save(any());
    }
}
