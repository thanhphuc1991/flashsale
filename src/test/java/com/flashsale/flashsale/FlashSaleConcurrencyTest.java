package com.flashsale.flashsale;

import com.flashsale.flashsale.repository.FlashSaleItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the core correctness requirement: under N concurrent buyers racing
 * for an item with a small stock limit, exactly `quantity_limit` reservations
 * succeed — never more — regardless of thread interleaving.
 *
 * Runs against an in-memory H2 database (DB_CLOSE_DELAY=-1 keeps it alive for
 * the whole test) so this needs no Docker/external DB. A unique DB name per
 * test run avoids collisions with other test classes' H2 instances.
 */
@SpringBootTest
class FlashSaleConcurrencyTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String dbName = "concurrency-test-" + UUID.randomUUID();
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    /**
     * A single @Modifying repository query called directly (with no enclosing
     * @Transactional, as production code always has via FlashSaleService) is
     * NOT reliably wrapped in a transaction by Spring Data's repository-proxy
     * defaults in every Spring Boot setup — it throws
     * "InvalidDataAccessApiUsageException: Executing an update/delete query".
     * This tiny concrete @Component with an explicit @Transactional method is
     * the always-correct way to guarantee a transaction for this test, since
     * @Transactional on a real Spring-managed bean method is always honored
     * by Spring AOP (unlike annotating a repository interface method, whose
     * transactional wrapping depends on Spring Data's own proxy machinery).
     */
    @TestConfiguration
    static class StockReservationTestConfig {
        @Bean
        StockReservationHelper stockReservationHelper(FlashSaleItemRepository itemRepository) {
            return new StockReservationHelper(itemRepository);
        }
    }

    static class StockReservationHelper {
        private final FlashSaleItemRepository itemRepository;

        StockReservationHelper(FlashSaleItemRepository itemRepository) {
            this.itemRepository = itemRepository;
        }

        @Transactional
        int reserve(Long itemId) {
            return itemRepository.tryReserveStock(itemId);
        }
    }

    @Autowired
    private StockReservationHelper stockReservationHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long itemId;
    private static final int STOCK_LIMIT = 10;
    private static final int CONCURRENT_BUYERS = 100;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("INSERT INTO flash_sale_window (name, start_time, end_time, status) " +
                "VALUES ('test-window', DATEADD('HOUR', -1, NOW()), DATEADD('HOUR', 1, NOW()), 'ACTIVE')");
        Long windowId = jdbcTemplate.queryForObject(
                "SELECT id FROM flash_sale_window WHERE name = 'test-window'", Long.class);

        jdbcTemplate.update("INSERT INTO flash_sale_item " +
                "(window_id, product_id, product_name, price, quantity_limit, quantity_sold, version) " +
                "VALUES (?, 999, 'Concurrency Test Item', 10.00, ?, 0, 0)", windowId, STOCK_LIMIT);
        itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM flash_sale_item WHERE product_id = 999", Long.class);
    }

    @Test
    void onlyExactlyStockLimitReservationsSucceedUnderConcurrentLoad() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_BUYERS);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < CONCURRENT_BUYERS; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int rows = stockReservationHelper.reserve(itemId);
                    if (rows > 0) successCount.incrementAndGet();
                } catch (InterruptedException ignored) {
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads at once to maximize race likelihood
        doneLatch.await();
        executor.shutdown();

        if (!errors.isEmpty()) {
            errors.get(0).printStackTrace();
            fail(errors.size() + " of " + CONCURRENT_BUYERS + " reservation attempts threw an exception; "
                    + "first cause: " + errors.get(0));
        }

        assertEquals(STOCK_LIMIT, successCount.get(),
                "Exactly quantity_limit purchases should succeed even with concurrent buyers racing");

        Integer finalSold = jdbcTemplate.queryForObject(
                "SELECT quantity_sold FROM flash_sale_item WHERE id = ?", Integer.class, itemId);
        assertEquals(STOCK_LIMIT, finalSold);
    }
}
