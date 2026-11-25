-- Catalog V102 added gym_entrance_history.category_id. Mirror it on gym_visits so settlement
-- can be reconciled per category with the catalog entrance history. Nullable and additive:
-- legacy rows and clients that do not send a category stay valid.
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS category_id BIGINT;
