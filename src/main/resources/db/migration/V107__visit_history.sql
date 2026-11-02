-- ===================================================
--  V107: QR visit history (successful visits + rejected attempts)
-- ===================================================

-- Gym name is snapshotted per visit so a later rename does not rewrite history.
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS gym_name VARCHAR(120);

-- History pages are read per user ordered by scan time.
CREATE INDEX IF NOT EXISTS idx_gym_visits_user_scanned
    ON gym_visits (user_id, checked_in_at DESC);

-- Append-only rejected QR attempts. Never used for limits or settlement.
CREATE TABLE IF NOT EXISTS visit_attempt (
    id              BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    gym_id          BIGINT      NOT NULL,
    gym_name        VARCHAR(120),
    package_code    VARCHAR(32),
    mode            VARCHAR(30) NOT NULL,
    reject_code     VARCHAR(48) NOT NULL,
    qr_scan_id      VARCHAR(120),
    scanned_at      TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_visit_attempt_user_scanned
    ON visit_attempt (user_id, scanned_at DESC);
CREATE INDEX IF NOT EXISTS idx_visit_attempt_sub_scanned
    ON visit_attempt (subscription_id, scanned_at DESC);

-- T-16: a retried scan is stored once. NULL ids stay unconstrained because the
-- mobile app may omit the id on older builds.
CREATE UNIQUE INDEX IF NOT EXISTS uq_visit_attempt_qr_scan
    ON visit_attempt (qr_scan_id)
    WHERE qr_scan_id IS NOT NULL;
