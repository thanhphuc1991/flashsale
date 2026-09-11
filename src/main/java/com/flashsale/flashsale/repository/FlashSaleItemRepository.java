package com.flashsale.flashsale.repository;

import com.flashsale.flashsale.domain.FlashSaleItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface FlashSaleItemRepository extends JpaRepository<FlashSaleItem, Long> {

    List<FlashSaleItem> findByWindowIdIn(List<Long> windowIds);

    Optional<FlashSaleItem> findByIdAndWindowId(Long id, Long windowId);

    /**
     * The core concurrency-safety guarantee for flash-sale purchases.
     *
     * This is a single atomic conditional UPDATE: the row is only modified if
     * quantity_sold is still below quantity_limit at the moment the DB
     * evaluates the WHERE clause. The database takes a row-level lock during the
     * UPDATE, so concurrent requests for the same item are serialized by the
     * database itself — no application-level lock/semaphore is needed, and it
     * works correctly across multiple stateless app instances.
     *
     * Returns the number of rows updated: 1 = stock reserved successfully,
     * 0 = sold out (someone else won the race, or the item never had stock).
     *
     * @Transactional is explicit here (rather than relying on Spring Data's
     * default repository-proxy transaction) so this single-statement
     * @Modifying query always runs inside its own committed transaction
     * regardless of how/where it's called from — important since this method
     * is invoked directly (without an enclosing @Transactional) from the
     * concurrency test.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE flash_sale_item
            SET quantity_sold = quantity_sold + 1
            WHERE id = :itemId AND quantity_sold < quantity_limit
            """, nativeQuery = true)
    int tryReserveStock(@Param("itemId") Long itemId);

    /** Compensating action if a purchase fails after stock was reserved. */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE flash_sale_item
            SET quantity_sold = quantity_sold - 1
            WHERE id = :itemId AND quantity_sold > 0
            """, nativeQuery = true)
    int releaseStock(@Param("itemId") Long itemId);
}
