-- V21: Bakcell design/API alignment (B3, B5, B6, B14)
-- 1. Per-locale media (banner for M2, hero for M3 story)
-- 2. Dark-background partner logo (+ campaign override)
-- 3. Story auto-close duration on popup config

CREATE TABLE IF NOT EXISTS promo_campaign_media (
    campaign_id UUID NOT NULL REFERENCES promo_campaign(id) ON DELETE CASCADE,
    locale VARCHAR(8) NOT NULL CHECK (locale IN ('az', 'en', 'ru')),
    banner_image_url TEXT,
    hero_image_url TEXT,
    PRIMARY KEY (campaign_id, locale)
);

ALTER TABLE promo_partner ADD COLUMN IF NOT EXISTS logo_on_dark_url TEXT;
ALTER TABLE promo_campaign ADD COLUMN IF NOT EXISTS logo_on_dark_url_override TEXT;
ALTER TABLE promo_campaign_popup ADD COLUMN IF NOT EXISTS duration_sec INT NOT NULL DEFAULT 7;
