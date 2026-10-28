-- T-16: idempotent QR scans.
-- The mobile client may retry a scan with the same qrScanId; only one visit must be
-- written per (subscription, scan). Legacy clients that do not send the id keep NULL
-- and are unaffected.
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS qr_scan_id VARCHAR(120);

CREATE UNIQUE INDEX IF NOT EXISTS uk_gym_visits_qr_scan
    ON gym_visits (subscription_id, qr_scan_id)
    WHERE qr_scan_id IS NOT NULL;
