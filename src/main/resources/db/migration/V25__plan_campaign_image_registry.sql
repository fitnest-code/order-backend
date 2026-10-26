CREATE TABLE IF NOT EXISTS plan_campaign_images (
    fs_id VARCHAR(32) PRIMARY KEY,
    uploaded_by BIGINT,
    uploaded_at TIMESTAMPTZ
);
