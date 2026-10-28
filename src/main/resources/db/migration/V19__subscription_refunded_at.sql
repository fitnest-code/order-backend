-- ===================================================
--  V19: durable refund marker on the subscription
-- ===================================================
--
-- A refund does not change subscriptions.status (it stays ACTIVE until the paid
-- period runs out), so the 24h home-gym reminder could still be sent after the
-- money was returned. The marker below is the durable "was refunded" signal.

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS refunded_at TIMESTAMP;

COMMENT ON COLUMN subscriptions.refunded_at IS
    'Set when the payment was refunded (PAYMENT_REFUND_CANCEL). Home-gym reminder and other post-purchase jobs skip refunded rows.';

CREATE INDEX IF NOT EXISTS idx_subscriptions_refunded_at
    ON subscriptions (refunded_at)
    WHERE refunded_at IS NOT NULL;
