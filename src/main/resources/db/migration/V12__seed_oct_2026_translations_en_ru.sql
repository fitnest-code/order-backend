-- Seed EN and RU translations for October 2026 Campaign
-- This migration adds EN and RU translations to complement the AZ-only V11 seed

INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
VALUES
-- Campaign base (EN)
('CAMPAIGN', '1', 'EN', 'title', 'More workouts, more opportunities!'),
('CAMPAIGN', '1', 'EN', 'description', 'Get extra months free when you buy 3, 6, or 12-month subscriptions!'),
('CAMPAIGN', '1', 'EN', 'ctaLabel', 'Learn More'),
('CAMPAIGN', '1', 'EN', 'confirmTitle', '{bonusMonths} month gift earned!'),
('CAMPAIGN', '1', 'EN', 'confirmBody', 'Your subscription has been extended by {bonusMonths} months from the October campaign.'),

-- Campaign base (RU)
('CAMPAIGN', '1', 'RU', 'title', 'Больше тренировок — больше возможностей!'),
('CAMPAIGN', '1', 'RU', 'description', 'Получите дополнительные месяцы бесплатно при покупке подписки на 3, 6 или 12 месяцев!'),
('CAMPAIGN', '1', 'RU', 'ctaLabel', 'Подробнее'),
('CAMPAIGN', '1', 'RU', 'confirmTitle', '{bonusMonths} месяц в подарок!'),
('CAMPAIGN', '1', 'RU', 'confirmBody', 'Ваша подписка продлена на {bonusMonths} месяцев по октябрьской акции.'),

-- Campaign offers (EN)
('CAMPAIGNOFFER', '1', 'EN', 'label', 'Buy 3 months + 1 month free'),
('CAMPAIGNOFFER', '2', 'EN', 'label', 'Buy 6 months + 2 months free'),
('CAMPAIGNOFFER', '3', 'EN', 'label', 'Buy 12 months + 3 months free'),

-- Campaign offers (RU)
('CAMPAIGNOFFER', '1', 'RU', 'label', 'Купи 3 месяца + 1 месяц в подарок'),
('CAMPAIGNOFFER', '2', 'RU', 'label', 'Купи 6 месяцев + 2 месяца в подарок'),
('CAMPAIGNOFFER', '3', 'RU', 'label', 'Купи 12 месяцев + 3 месяца в подарок'),

-- Campaign terms (EN)
('CAMPAIGNTERM', '1', 'EN', 'text', 'Campaign is valid only in October.'),
('CAMPAIGNTERM', '2', 'EN', 'text', 'Each user can redeem only once.'),
('CAMPAIGNTERM', '3', 'EN', 'text', '1-month subscription is not eligible for the campaign.'),
('CAMPAIGNTERM', '4', 'EN', 'text', 'Gift months are added automatically.'),

-- Campaign terms (RU)
('CAMPAIGNTERM', '1', 'RU', 'text', 'Акция действует только в октябре.'),
('CAMPAIGNTERM', '2', 'RU', 'text', 'Каждый пользователь может воспользоваться один раз.'),
('CAMPAIGNTERM', '3', 'RU', 'text', 'Подписка на 1 месяц не участвует в акции.'),
('CAMPAIGNTERM', '4', 'RU', 'text', 'Подарные месяцы добавляются автоматически.')
ON CONFLICT (entity_type, entity_id, field_name, language_code) DO NOTHING;