-- Seed data for local/demo runs. Written to be idempotent (guarded by
-- WHERE NOT EXISTS) since spring.sql.init.mode=always re-runs this file on
-- every app startup, and ddl-auto=update never drops existing data.

INSERT INTO flash_sale_window (name, start_time, end_time, status)
SELECT 'Always-on demo window', DATEADD('DAY', -1, NOW()), DATEADD('DAY', 30, NOW()), 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM flash_sale_window WHERE name = 'Always-on demo window');

INSERT INTO flash_sale_item (window_id, product_id, product_name, price, quantity_limit, quantity_sold, version)
SELECT w.id, 101, 'Wireless Earbuds Pro', 49.99, 50, 0, 0
FROM flash_sale_window w
WHERE w.name = 'Always-on demo window'
  AND NOT EXISTS (SELECT 1 FROM flash_sale_item WHERE product_id = 101 AND window_id = w.id);

INSERT INTO flash_sale_item (window_id, product_id, product_name, price, quantity_limit, quantity_sold, version)
SELECT w.id, 102, 'Mechanical Keyboard', 89.99, 30, 0, 0
FROM flash_sale_window w
WHERE w.name = 'Always-on demo window'
  AND NOT EXISTS (SELECT 1 FROM flash_sale_item WHERE product_id = 102 AND window_id = w.id);

INSERT INTO flash_sale_item (window_id, product_id, product_name, price, quantity_limit, quantity_sold, version)
SELECT w.id, 103, 'Smart Watch Lite', 129.99, 10, 0, 0
FROM flash_sale_window w
WHERE w.name = 'Always-on demo window'
  AND NOT EXISTS (SELECT 1 FROM flash_sale_item WHERE product_id = 103 AND window_id = w.id);
