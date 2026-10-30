-- ===================================================
--  V16: Home Gym v3 (R19-R21) — 30 visits / 1 per day
-- ===================================================

-- 1. R19 adds a new selection source.
ALTER TABLE subscription_home_gym DROP CONSTRAINT IF EXISTS chk_home_gym_source;
ALTER TABLE subscription_home_gym
    ADD CONSTRAINT chk_home_gym_source
        CHECK (source IN ('POST_PAYMENT', 'SUBSCRIPTION_SCREEN', 'FIRST_QR', 'SUGGESTED', 'ADMIN'));

-- 2. R21 daily gate: countable home visits of the subscription at a gym on one Baku day.
CREATE INDEX IF NOT EXISTS idx_gym_visits_sub_type_checked
    ON gym_visits (subscription_id, entry_type, checked_in_at);

CREATE INDEX IF NOT EXISTS idx_gym_visits_sub_type_day
    ON gym_visits (subscription_id, entry_type, CAST(checked_in_at AS DATE));
