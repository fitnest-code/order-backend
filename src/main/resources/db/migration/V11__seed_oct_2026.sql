-- Seed data for October 2026 Campaign (DRAFT status by default)
INSERT INTO campaigns (id, code, status, start_at, end_at, banner_image_url, cta_target, one_per_user, created_at, updated_at)
VALUES (1, 'OCT_2026', 'DRAFT', '2026-10-01 00:00:00', '2026-10-31 23:59:59', 'https://media.fitnest.az/stream/october_banner.png', 'CAMPAIGN_DETAIL', TRUE, NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

INSERT INTO campaign_offers (campaign_id, base_duration_months, bonus_months)
VALUES 
(1, 3, 1),
(1, 6, 2),
(1, 12, 3)
ON CONFLICT (campaign_id, base_duration_months) DO NOTHING;

INSERT INTO campaign_terms (campaign_id, sort_order, is_positive)
VALUES 
(1, 1, TRUE),
(1, 2, TRUE),
(1, 3, TRUE),
(1, 4, TRUE)
ON CONFLICT (campaign_id, sort_order) DO NOTHING;

-- Seed translations matching UI design mockup
INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
VALUES
('CAMPAIGN', '1', 'AZ', 'title', 'Daha çox idman, daha çox imkan!'),
('CAMPAIGN', '1', 'AZ', 'description', '3, 6 və 12 aylıq abunəliklərdə əlavə ayları bizdən hədiyyə al!'),
('CAMPAIGN', '1', 'AZ', 'ctaLabel', 'Ətraflı Bax'),

('CAMPAIGNOFFER', '1', 'AZ', 'label', '3 aylıq al + 1 ay hədiyyə'),
('CAMPAIGNOFFER', '2', 'AZ', 'label', '6 aylıq al + 2 ay hədiyyə'),
('CAMPAIGNOFFER', '3', 'AZ', 'label', '12 aylıq al + 3 ay hədiyyə'),

('CAMPAIGNTERM', '1', 'AZ', 'text', 'Kampaniya yalnız oktyabr ayında keçərlidir.'),
('CAMPAIGNTERM', '2', 'AZ', 'text', 'Hər istifadəçi 1 dəfə yararlana bilər.'),
('CAMPAIGNTERM', '3', 'AZ', 'text', '1 aylıq abunəlik kampaniyaya daxil deyil.'),
('CAMPAIGNTERM', '4', 'AZ', 'text', 'Hədiyyə ayları avtomatik əlavə olunur.')
ON CONFLICT (entity_type, entity_id, field_name, language_code) DO NOTHING;
