-- Seed 5 root category cho dev environment. Production sẽ load từ
-- import file riêng — đừng bao giờ migrate seed prod data qua Flyway
-- repeatable migration trừ khi data đó là "config-as-code".
INSERT INTO categories (id, name, slug, parent_id, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Electronics', 'electronics', NULL, NOW(), NOW()),
    ('00000000-0000-0000-0000-000000000002', 'Fashion',     'fashion',     NULL, NOW(), NOW()),
    ('00000000-0000-0000-0000-000000000003', 'Home & Living', 'home-living', NULL, NOW(), NOW()),
    ('00000000-0000-0000-0000-000000000004', 'Books',       'books',       NULL, NOW(), NOW()),
    ('00000000-0000-0000-0000-000000000005', 'Sports',      'sports',      NULL, NOW(), NOW());
