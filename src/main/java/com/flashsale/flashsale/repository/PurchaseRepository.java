package com.flashsale.flashsale.repository;

import com.flashsale.flashsale.domain.Purchase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {
    Optional<Purchase> findByUserIdAndPurchaseDate(Long userId, LocalDate purchaseDate);
    boolean existsByIdempotencyKey(String idempotencyKey);
}
