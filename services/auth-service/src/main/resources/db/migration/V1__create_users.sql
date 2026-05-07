-- Day 2 — auth-service base schema.
-- email UNIQUE: ngăn double registration (race condition cũng được DB chặn,
--               không chỉ dựa vào application-level check).
-- password_hash: BCrypt output ~60 chars; để 100 cho tương lai (argon2 dài hơn).
CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(100) NOT NULL,
    role            VARCHAR(32)  NOT NULL DEFAULT 'USER',
    token_version   INTEGER      NOT NULL DEFAULT 0,    -- bump để invalidate toàn bộ JWT cũ (vd: password change)
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    version         BIGINT       NOT NULL DEFAULT 0     -- @Version optimistic lock
);

CREATE INDEX idx_users_email_lower ON users (LOWER(email));
