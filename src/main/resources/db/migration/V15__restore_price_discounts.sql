-- V15: Restore prod subscription discounts (price_discounted).
-- Incident (2026-10-01): all membership_plan_options.price_discounted were NULL in
-- production, so the public API returned effective == base for every duration and
-- the landing page showed a flat per-month price (e.g. Bronze 55 for 1/3/6/12).
-- These are the live business prices observed in prod before the wipe (they match
-- real SUCCESS payments, e.g. 132 = Bronze 3m pay 831, 475 = Bronze 12m pay 257,
-- 2300 = Platinum 12m pay 278). Idempotent: only touches rows that differ.

UPDATE membership_plan_options SET price_discounted = 47.00   WHERE id = 1  AND price_discounted IS DISTINCT FROM 47.00;
UPDATE membership_plan_options SET price_discounted = 132.00  WHERE id = 5  AND price_discounted IS DISTINCT FROM 132.00;
UPDATE membership_plan_options SET price_discounted = 248.00  WHERE id = 8  AND price_discounted IS DISTINCT FROM 248.00;
UPDATE membership_plan_options SET price_discounted = 475.00  WHERE id = 9  AND price_discounted IS DISTINCT FROM 475.00;
UPDATE membership_plan_options SET price_discounted = 72.00   WHERE id = 2  AND price_discounted IS DISTINCT FROM 72.00;
UPDATE membership_plan_options SET price_discounted = 204.00  WHERE id = 7  AND price_discounted IS DISTINCT FROM 204.00;
UPDATE membership_plan_options SET price_discounted = 398.00  WHERE id = 11 AND price_discounted IS DISTINCT FROM 398.00;
UPDATE membership_plan_options SET price_discounted = 775.00  WHERE id = 10 AND price_discounted IS DISTINCT FROM 775.00;
UPDATE membership_plan_options SET price_discounted = 132.00  WHERE id = 3  AND price_discounted IS DISTINCT FROM 132.00;
UPDATE membership_plan_options SET price_discounted = 372.00  WHERE id = 6  AND price_discounted IS DISTINCT FROM 372.00;
UPDATE membership_plan_options SET price_discounted = 726.00  WHERE id = 12 AND price_discounted IS DISTINCT FROM 726.00;
UPDATE membership_plan_options SET price_discounted = 1414.00 WHERE id = 13 AND price_discounted IS DISTINCT FROM 1414.00;
UPDATE membership_plan_options SET price_discounted = 205.00  WHERE id = 4  AND price_discounted IS DISTINCT FROM 205.00;
UPDATE membership_plan_options SET price_discounted = 608.00  WHERE id = 17 AND price_discounted IS DISTINCT FROM 608.00;
UPDATE membership_plan_options SET price_discounted = 1215.00 WHERE id = 18 AND price_discounted IS DISTINCT FROM 1215.00;
UPDATE membership_plan_options SET price_discounted = 2300.00 WHERE id = 19 AND price_discounted IS DISTINCT FROM 2300.00;
