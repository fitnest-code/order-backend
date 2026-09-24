-- Hibernate @Version increment NPEs when the in-memory/DB value is NULL.
-- Backfill legacy rows and harden NOT NULL defaults (idempotent).

UPDATE subscriptions
SET version = 0
WHERE version IS NULL;

ALTER TABLE subscriptions
    ALTER COLUMN version SET DEFAULT 0;

ALTER TABLE subscriptions
    ALTER COLUMN version SET NOT NULL;

UPDATE subscription_freeze_entitlements
SET version = 0
WHERE version IS NULL;

ALTER TABLE subscription_freeze_entitlements
    ALTER COLUMN version SET DEFAULT 0;

ALTER TABLE subscription_freeze_entitlements
    ALTER COLUMN version SET NOT NULL;

UPDATE subscription_freezes
SET version = 0
WHERE version IS NULL;

ALTER TABLE subscription_freezes
    ALTER COLUMN version SET DEFAULT 0;

ALTER TABLE subscription_freezes
    ALTER COLUMN version SET NOT NULL;
