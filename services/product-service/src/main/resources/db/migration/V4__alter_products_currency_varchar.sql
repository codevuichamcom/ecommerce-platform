-- Currency phải là VARCHAR(3), không CHAR(3).
-- CHAR(3) (bpchar) pad space tới đủ 3 ký tự → "US" thành "US " → compare
-- bug ở downstream (order-service so currency code khi snapshot price).
-- Entity Product.currency = String length=3 → Hibernate validate expect
-- VARCHAR(3). Mismatch khiến app fail boot ngay khi turn on
-- spring.jpa.hibernate.ddl-auto=validate.

ALTER TABLE products
    ALTER COLUMN currency TYPE VARCHAR(3) USING TRIM(currency);

-- TRIM USING để xử rows đã insert trước migration này — nếu CHAR(3) đã
-- pad space thì TRIM cắt bỏ. Idempotent với rows mới (không có space).

ALTER TABLE products
    ALTER COLUMN currency SET DEFAULT 'VND';
