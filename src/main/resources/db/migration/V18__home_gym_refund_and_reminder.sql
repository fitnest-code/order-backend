-- ===================================================
--  V18: home gym — refund end reason and 24h reminder
-- ===================================================

-- 1. Refund gets its own end reason (analytics + push rules, spec "Kənar hallar").
ALTER TABLE subscription_home_gym DROP CONSTRAINT IF EXISTS chk_home_gym_end_reason;
ALTER TABLE subscription_home_gym
    ADD CONSTRAINT chk_home_gym_end_reason
        CHECK (end_reason IS NULL OR end_reason IN
               ('SWITCH', 'CHANGE', 'UPGRADE_NOT_IN_PACKAGE', 'GYM_HOME_DISABLED',
                'SUBSCRIPTION_ENDED', 'REFUND'));

-- 2. One-time "select your home gym" reminder, sent 24h after purchase.
ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS home_gym_reminder_sent_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_subscriptions_home_gym_reminder
    ON subscriptions (status, start_at)
    WHERE home_gym_reminder_sent_at IS NULL;
