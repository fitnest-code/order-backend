-- BRD v1.2: raise per-tier freeze allowance and switch multi-month
-- subscriptions to a non-cumulative monthly budget.
--
-- Only ACTIVE/FROZEN entitlements are raised. Existing consumed_days /
-- reserved_days are intentionally untouched: already-used freeze days remain
-- used. Freeze history rows and expired subscriptions are not modified.

ALTER TABLE subscription_freeze_entitlements
    ADD COLUMN IF NOT EXISTS allowance_month VARCHAR(7);

UPDATE subscription_freeze_entitlements e
-- v1.1 could accumulate allowances across months. Never lower the budget below
-- already used + reserved days (chk_entitlement_budget); the next monthly reset
-- normalises it back to the single-month allowance.
SET total_days = GREATEST(
        CASE
            WHEN lower(sp.name) LIKE '%platinum%' THEN 12
            WHEN lower(sp.name) LIKE '%gold%'     THEN 10
            WHEN lower(sp.name) LIKE '%silver%'   THEN 7
            WHEN lower(sp.name) LIKE '%bronze%'   THEN 5
        END,
        e.consumed_days + e.reserved_days
    ),
    policy_version  = 'v1.2',
    allowance_month = to_char(current_date, 'YYYY-MM'),
    updated_at      = NOW()
FROM subscriptions s
JOIN subscription_packages sp ON sp.id = s.plan_id
WHERE e.subscription_id = s.subscription_id
  AND s.status IN ('ACTIVE', 'FROZEN')
  AND (
      lower(sp.name) LIKE '%platinum%'
      OR lower(sp.name) LIKE '%gold%'
      OR lower(sp.name) LIKE '%silver%'
      OR lower(sp.name) LIKE '%bronze%'
  );

-- Legacy rows whose package cannot be resolved still need a stable month so the
-- monthly job does not reset them repeatedly on its first run after deploy.
UPDATE subscription_freeze_entitlements
SET allowance_month = to_char(current_date, 'YYYY-MM')
WHERE allowance_month IS NULL;

COMMENT ON COLUMN subscription_freeze_entitlements.allowance_month IS
    'YYYY-MM of the current freeze allowance; unused days do not roll over';
