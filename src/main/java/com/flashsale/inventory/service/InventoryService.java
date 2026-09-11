package com.flashsale.inventory.service;

import com.flashsale.inventory.domain.Inventory;
import com.flashsale.inventory.domain.ProcessedEvent;
import com.flashsale.inventory.repository.InventoryRepository;
import com.flashsale.inventory.repository.ProcessedEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ProcessedEventRepository processedEventRepository;

    public InventoryService(InventoryRepository inventoryRepository,
                             ProcessedEventRepository processedEventRepository) {
        this.inventoryRepository = inventoryRepository;
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Applies a stock delta exactly once per eventId.
     * Returns false if the event was already processed (duplicate delivery) —
     * caller should treat that as a normal no-op, not an error.
     */
    @Transactional
    public boolean applyOnce(String eventId, Long productId, int delta) {
        try {
            processedEventRepository.save(new ProcessedEvent(eventId));
        } catch (DataIntegrityViolationException e) {
            return false; // already processed this exact event
        }

        Inventory inventory = inventoryRepository.findById(productId)
                .orElseGet(() -> new Inventory(productId, 0));
        inventory.adjust(delta);
        inventoryRepository.save(inventory);
        return true;
    }
}
