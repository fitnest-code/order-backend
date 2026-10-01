-- V14: October campaign title/description fix + offer label repair.
-- Product feedback (2026-09-30):
--  1. Title everywhere was the long "Oktyabr kampaniyasi: elave aylar hediyye"-style
--     text. Shorten to just "Oktyabr kampaniyasi" (AZ) / "October campaign" (EN) /
--     "Oktyabrskaya aktsiya" (RU).
--  2. Description must read "Extra months as a gift from Fitnest on 3, 6, and
--     12-month subscriptions!" (EN) with matching AZ/RU.
--  3. Offer EN/RU labels in dev DB are broken ("3 1 month"); repair by base duration.
--  4. Missing AZ confirmTitle/confirmBody (banner) -> add with {bonusMonths} placeholder.
-- Idempotent: INSERT ... ON CONFLICT DO UPDATE + UPDATE-by-join for offers.

-- 1. Campaign titles (short names).
INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
VALUES
    ('CAMPAIGN', '1', 'AZ', 'title', 'Oktyabr kampaniyası'),
    ('CAMPAIGN', '1', 'EN', 'title', 'October campaign'),
    ('CAMPAIGN', '1', 'RU', 'title', 'Октябрьская акция')
ON CONFLICT (entity_type, entity_id, field_name, language_code)
DO UPDATE SET field_value = EXCLUDED.field_value;

-- 2. Campaign descriptions.
INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
VALUES
    ('CAMPAIGN', '1', 'AZ', 'description', 'Fitnest-dən 3, 6 və 12 aylıq abunəliklərdə əlavə aylar hədiyyə!'),
    ('CAMPAIGN', '1', 'EN', 'description', 'Extra months as a gift from Fitnest on 3, 6, and 12-month subscriptions!'),
    ('CAMPAIGN', '1', 'RU', 'description', 'Дополнительные месяцы в подарок от Fitnest при подписке на 3, 6 и 12 месяцев!')
ON CONFLICT (entity_type, entity_id, field_name, language_code)
DO UPDATE SET field_value = EXCLUDED.field_value;

-- 3. Confirm banner AZ (EN/RU already seeded in V12).
INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
VALUES
    ('CAMPAIGN', '1', 'AZ', 'confirmTitle', '{bonusMonths} ay hədiyyə'),
    ('CAMPAIGN', '1', 'AZ', 'confirmBody', 'Oktyabr kampaniyasından abunəliyiniz {bonusMonths} ay uzadılacaq')
ON CONFLICT (entity_type, entity_id, field_name, language_code)
DO UPDATE SET field_value = EXCLUDED.field_value;

-- 4. Repair offer labels by base duration (robust to duplicate offer rows 4/5/6
-- created via admin in dev). All offers of campaign 1 with the same base duration
-- get the same correct label per language.
UPDATE translations t
SET field_value = CASE o.base_duration_months
    WHEN 3 THEN '3 aylıq al + 1 ay hədiyyə'
    WHEN 6 THEN '6 aylıq al + 2 ay hədiyyə'
    WHEN 12 THEN '12 aylıq al + 3 ay hədiyyə'
    ELSE t.field_value END
FROM campaign_offers o
JOIN campaigns c ON c.id = o.campaign_id AND c.code = 'OCT_2026'
WHERE t.entity_type = 'CAMPAIGNOFFER'
  AND t.field_name = 'label'
  AND t.language_code = 'AZ'
  AND t.entity_id = o.id::text;

UPDATE translations t
SET field_value = CASE o.base_duration_months
    WHEN 3 THEN 'Buy 3 months + 1 month free'
    WHEN 6 THEN 'Buy 6 months + 2 months free'
    WHEN 12 THEN 'Buy 12 months + 3 months free'
    ELSE t.field_value END
FROM campaign_offers o
JOIN campaigns c ON c.id = o.campaign_id AND c.code = 'OCT_2026'
WHERE t.entity_type = 'CAMPAIGNOFFER'
  AND t.field_name = 'label'
  AND t.language_code = 'EN'
  AND t.entity_id = o.id::text;

UPDATE translations t
SET field_value = CASE o.base_duration_months
    WHEN 3 THEN 'Купи 3 месяца + 1 месяц в подарок'
    WHEN 6 THEN 'Купи 6 месяцев + 2 месяца в подарок'
    WHEN 12 THEN 'Купи 12 месяцев + 3 месяца в подарок'
    ELSE t.field_value END
FROM campaign_offers o
JOIN campaigns c ON c.id = o.campaign_id AND c.code = 'OCT_2026'
WHERE t.entity_type = 'CAMPAIGNOFFER'
  AND t.field_name = 'label'
  AND t.language_code = 'RU'
  AND t.entity_id = o.id::text;

-- Insert labels for any offer rows missing a translation (e.g. admin-created 4/5/6).
INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
SELECT 'CAMPAIGNOFFER', o.id::text, l.lang, 'label',
    CASE WHEN l.lang = 'AZ' THEN CASE o.base_duration_months
        WHEN 3 THEN '3 aylıq al + 1 ay hədiyyə'
        WHEN 6 THEN '6 aylıq al + 2 ay hədiyyə'
        WHEN 12 THEN '12 aylıq al + 3 ay hədiyyə' END
    WHEN l.lang = 'EN' THEN CASE o.base_duration_months
        WHEN 3 THEN 'Buy 3 months + 1 month free'
        WHEN 6 THEN 'Buy 6 months + 2 months free'
        WHEN 12 THEN 'Buy 12 months + 3 months free' END
    WHEN l.lang = 'RU' THEN CASE o.base_duration_months
        WHEN 3 THEN 'Купи 3 месяца + 1 месяц в подарок'
        WHEN 6 THEN 'Купи 6 месяцев + 2 месяца в подарок'
        WHEN 12 THEN 'Купи 12 месяцев + 3 месяца в подарок' END
    END
FROM campaign_offers o
JOIN campaigns c ON c.id = o.campaign_id AND c.code = 'OCT_2026'
CROSS JOIN (VALUES ('AZ'), ('EN'), ('RU')) AS l(lang)
WHERE NOT EXISTS (
    SELECT 1 FROM translations t
    WHERE t.entity_type = 'CAMPAIGNOFFER'
      AND t.entity_id = o.id::text
      AND t.language_code = l.lang
      AND t.field_name = 'label'
)
ON CONFLICT (entity_type, entity_id, field_name, language_code) DO NOTHING;

-- 5. Delete orphan CAMPAIGNOFFER translations pointing at offer IDs that no longer
-- exist (dev DB has stale rows 1/2/3 with broken "3 1 month" labels; live offers
-- are 4/5/6, already repaired above).
DELETE FROM translations
WHERE entity_type = 'CAMPAIGNOFFER'
  AND field_name = 'label'
  AND NOT EXISTS (
      SELECT 1 FROM campaign_offers o
      WHERE o.id::text = translations.entity_id
  );
