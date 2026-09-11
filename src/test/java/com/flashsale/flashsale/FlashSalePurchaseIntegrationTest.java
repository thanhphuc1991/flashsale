package com.flashsale.flashsale;

import com.flashsale.common.security.JwtService;
import com.flashsale.flashsale.dto.PurchaseRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end (real HTTP + real, in-process H2 DB) coverage for the two
 * purchase rules that are easy to get wrong at the API layer even if the DB
 * constraints are correct: the daily purchase limit, and idempotent-retry
 * handling. No Docker/external DB needed — H2 in-memory, unique per test run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FlashSalePurchaseIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String dbName = "purchase-it-test-" + UUID.randomUUID();
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private String accessToken;
    private Long itemAId;
    private Long itemBId;

    @BeforeEach
    void setUp() {
        // Fresh verified user per test.
        String email = "buyer-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (identifier, identifier_type, password_hash, is_verified, balance, status, created_at, updated_at) " +
                        "VALUES (?, 'EMAIL', ?, true, 1000000, 'ACTIVE', NOW(), NOW())",
                email, passwordEncoder.encode("password123"));
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE identifier = ?", Long.class, email);
        accessToken = jwtService.generateAccessToken(userId, email);

        // Fresh active window with two items with ample stock, isolated to this test run.
        String windowName = "it-window-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO flash_sale_window (name, start_time, end_time, status) " +
                        "VALUES (?, DATEADD('HOUR', -1, NOW()), DATEADD('HOUR', 1, NOW()), 'ACTIVE')",
                windowName);
        Long windowId = jdbcTemplate.queryForObject(
                "SELECT id FROM flash_sale_window WHERE name = ?", Long.class, windowName);

        jdbcTemplate.update("INSERT INTO flash_sale_item " +
                        "(window_id, product_id, product_name, price, quantity_limit, quantity_sold, version) " +
                        "VALUES (?, 9001, 'IT Item A', 10.00, 100, 0, 0)", windowId);
        itemAId = jdbcTemplate.queryForObject(
                "SELECT id FROM flash_sale_item WHERE product_id = 9001 AND window_id = ?", Long.class, windowId);

        jdbcTemplate.update("INSERT INTO flash_sale_item " +
                        "(window_id, product_id, product_name, price, quantity_limit, quantity_sold, version) " +
                        "VALUES (?, 9002, 'IT Item B', 10.00, 100, 0, 0)", windowId);
        itemBId = jdbcTemplate.queryForObject(
                "SELECT id FROM flash_sale_item WHERE product_id = 9002 AND window_id = ?", Long.class, windowId);
    }

    @Test
    void firstPurchaseSucceeds() {
        ResponseEntity<Map> response = purchase(itemAId, "it-key-" + UUID.randomUUID());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCESS", response.getBody().get("status"));
    }

    @Test
    void secondPurchaseSameDayIsRejectedEvenForADifferentItem() {
        ResponseEntity<Map> first = purchase(itemAId, "it-key-" + UUID.randomUUID());
        assertEquals(HttpStatus.OK, first.getStatusCode());

        ResponseEntity<Map> second = purchase(itemBId, "it-key-" + UUID.randomUUID());
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertEquals("DAILY_LIMIT_REACHED", second.getBody().get("errorCode"));
    }

    @Test
    void retryingWithSameIdempotencyKeyIsRejectedWithoutDoubleSelling() {
        String idempotencyKey = "it-key-" + UUID.randomUUID();

        ResponseEntity<Map> first = purchase(itemAId, idempotencyKey);
        assertEquals(HttpStatus.OK, first.getStatusCode());

        ResponseEntity<Map> retry = purchase(itemAId, idempotencyKey);
        assertEquals(HttpStatus.CONFLICT, retry.getStatusCode());
        assertEquals("DUPLICATE_REQUEST", retry.getBody().get("errorCode"));

        Integer sold = jdbcTemplate.queryForObject(
                "SELECT quantity_sold FROM flash_sale_item WHERE id = ?", Integer.class, itemAId);
        assertEquals(1, sold, "Stock must only be decremented once despite the retried request");
    }

    @Test
    void purchaseRequiresAuthentication() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<PurchaseRequest> entity = new HttpEntity<>(
                new PurchaseRequest(itemAId, "no-auth-key"), headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/flash-sale/purchase", entity, Map.class);

        // No httpBasic/formLogin configured -> Spring Security's default entry
        // point for an unauthenticated request on a protected route is 403.
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    private ResponseEntity<Map> purchase(Long itemId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        HttpEntity<PurchaseRequest> entity = new HttpEntity<>(
                new PurchaseRequest(itemId, idempotencyKey), headers);
        return restTemplate.postForEntity("/api/v1/flash-sale/purchase", entity, Map.class);
    }
}
