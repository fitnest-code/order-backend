-- V19: Promo module follow-ups
-- 1. plan_ids UUID[] -> TEXT[] (catalog package IDs are numeric strings, mobile sends string codes like 3m-bronze)
-- 2. popup plan_id UUID -> TEXT (same reason)
-- 3. Indexes for grant order lookups (reserve/redeem path must not full-scan)

ALTER TABLE promo_campaign ALTER COLUMN plan_ids TYPE TEXT[] USING plan_ids::text[];
ALTER TABLE promo_campaign_popup ALTER COLUMN plan_id TYPE TEXT USING plan_id::text;

CREATE INDEX IF NOT EXISTS idx_promo_grant_reserved_order ON promo_grant(reserved_order_id);
CREATE INDEX IF NOT EXISTS idx_promo_grant_redeemed_order ON promo_grant(redeemed_order_id);

-- 4. Report retention (NFR-3): drop stored problem-row CSVs older than 7 days (runs from scheduler, idempotent helper)
-- (no schema change; documented here for ops)
