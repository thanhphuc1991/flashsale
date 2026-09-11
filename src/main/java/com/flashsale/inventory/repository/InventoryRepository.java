package com.flashsale.inventory.repository;

import com.flashsale.inventory.domain.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
}
