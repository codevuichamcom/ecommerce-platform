-- Refresh token store.
--
-- Quan trọng:
--   1. token_hash UNIQUE — không lưu plaintext (giống password). DB leak
--      cũng không issue được token mới.
--   2. revoked_at NULLable — atomic rotation: UPDATE ... SET revoked_at = NOW()
--      WHERE token_hash = ? AND revoked_at IS NULL → chỉ thành công 1 lần.
--      2 tab cùng /refresh → chỉ 1 thắng (xem issues/02-token-refresh-race-condition.md).
--   3. Index (user_id, expires_at) cho cleanup job + revoke-all-by-user.
CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   VARCHAR(128) NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(64),
    updated_by   VARCHAR(64),
    version      BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_refresh_tokens_user_expires ON refresh_tokens (user_id, expires_at);
