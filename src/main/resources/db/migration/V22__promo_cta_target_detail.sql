-- V22: allow PROMO_DETAIL CTA target (B1: popup "Daha cox" opens M3 partner screen)
ALTER TABLE promo_campaign_popup DROP CONSTRAINT IF EXISTS promo_campaign_popup_cta_target_check;
ALTER TABLE promo_campaign_popup ADD CONSTRAINT promo_campaign_popup_cta_target_check
    CHECK (cta_target IN ('PLANS', 'PLAN_DETAIL', 'URL', 'PROMO_DETAIL'));
