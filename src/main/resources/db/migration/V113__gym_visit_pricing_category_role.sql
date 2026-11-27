-- v6 primary check-in: MAIN vs SUB role for visit-limit gates and settlement.
-- Nullable so v4/v5 priced rows stay valid without backfill.
ALTER TABLE gym_visit_pricing_v4
    ADD COLUMN IF NOT EXISTS category_role VARCHAR(10);

COMMENT ON COLUMN gym_visit_pricing_v4.category_role IS
    'MAIN|SUB from catalog v6 check-in; NULL = legacy v4/v5 rows';
