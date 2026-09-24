-- V7: composite indexes for freeze hot paths (eligibility, terminate-by-user, history).
-- Partial unique ACTIVE freeze + plan_end_at index already exist from V5/V6.

CREATE INDEX IF NOT EXISTS idx_freeze_user_status
    ON subscription_freezes(user_id, status);

CREATE INDEX IF NOT EXISTS idx_freeze_user_created
    ON subscription_freezes(user_id, created_at DESC);

-- Idempotency lookup is unique on (user_id, endpoint, idempotency_key) — ensure supporting index
CREATE INDEX IF NOT EXISTS idx_idempotency_lookup
    ON freeze_idempotency_records(user_id, endpoint, idempotency_key);
