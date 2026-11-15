-- Black Friday landing screen copy (GET /api/v2/campaigns/active/landing).
-- Kept in a side table (one row per campaign + locale) instead of 6+ more columns on
-- pricing_campaigns: the screen has many strings x 3 languages and ops edit them together.
--
-- Templates are rendered by the service so dates/percents never drift from the campaign row:
--   {maxPercent} {months} {percent}  -> numbers (trailing zeros stripped: 20.00 -> 20)
--   {startsAt} {endsAt}              -> dd.MM.yyyy in Asia/Baku
--   {endsAtTime}                     -> HH:mm in Asia/Baku
--
-- terms_json is a JSON array of strings stored as TEXT (not JSONB): it is only ever read
-- whole and parsed by Jackson, so JSONB would add Hibernate type mapping for no benefit.
--
-- Ops edit path (no deploy needed), e.g.:
--   UPDATE pricing_campaign_landing_i18n SET headline = '...'
--    WHERE locale = 'az' AND campaign_id = (SELECT id FROM pricing_campaigns WHERE code = 'bf_2026');

CREATE TABLE IF NOT EXISTS pricing_campaign_landing_i18n (
    id                    BIGSERIAL PRIMARY KEY,
    campaign_id           UUID         NOT NULL REFERENCES pricing_campaigns (id) ON DELETE CASCADE,
    locale                VARCHAR(2)   NOT NULL,
    title                 VARCHAR(255) NOT NULL,
    headline              VARCHAR(255) NOT NULL,
    subtitle              TEXT         NOT NULL,
    option_label_template VARCHAR(255) NOT NULL,
    cta_title             VARCHAR(255) NOT NULL,
    terms_json            TEXT         NOT NULL DEFAULT '[]',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_pricing_landing_campaign_locale UNIQUE (campaign_id, locale),
    CONSTRAINT chk_pricing_landing_locale CHECK (locale IN ('az', 'en', 'ru'))
);

-- Seed for bf_2026 (AZ = design copy, EN/RU = same meaning). Idempotent like V108.
INSERT INTO pricing_campaign_landing_i18n
    (campaign_id, locale, title, headline, subtitle, option_label_template, cta_title, terms_json)
SELECT c.id, v.locale, v.title, v.headline, v.subtitle, v.option_label_template, v.cta_title, v.terms_json
FROM pricing_campaigns c
CROSS JOIN (VALUES
    ('az',
     'Qara Cümə',
     '{maxPercent}%-dək endirim!',
     '3, 6 və 12 aylıq abunəlikləri Qara Cümə qiyməti ilə al.',
     '{months} aylıq al - {percent} % endirim',
     'Planını seç',
     -- AZ terms match the BF landing design (WhatsApp mock 2026-11-15).
     '["Kampaniya {startsAt}–{endsAt} ({endsAt} {endsAtTime}, Bakı vaxtı) keçərlidir.",
       "1 aylıq abunəlik kampaniyaya daxil deyil.",
       "Hər istifadəçi 1 dəfə yararlana bilər.",
       "FitCoin endirimli məbləğə tətbiq olunur.",
       "Endirim ödəniş zamanı avtomatik tətbiq olunur."]'),
    ('en',
     'Black Friday',
     'Up to {maxPercent}% off!',
     'Get 3, 6 and 12 month memberships at the Black Friday price.',
     'Get {months} months - {percent} % off',
     'Choose your plan',
     '["Campaign is valid {startsAt}–{endsAt} ({endsAt} {endsAtTime}, Baku time).",
       "1-month subscriptions are not included.",
       "Each user can use the offer once.",
       "FitCoin is applied to the discounted amount.",
       "The discount is applied automatically at payment."]'),
    ('ru',
     'Чёрная пятница',
     'Скидки до {maxPercent}%!',
     'Приобретайте абонементы на 3, 6 и 12 месяцев по цене Чёрной пятницы.',
     'Купить на {months} мес. - скидка {percent} %',
     'Выбрать план',
     '["Акция действует {startsAt}–{endsAt} ({endsAt} {endsAtTime}, время Баку).",
       "Месячная подписка в акцию не входит.",
       "Каждый пользователь может воспользоваться один раз.",
       "FitCoin применяется к сумме со скидкой.",
       "Скидка применяется автоматически при оплате."]')
) AS v (locale, title, headline, subtitle, option_label_template, cta_title, terms_json)
WHERE c.code = 'bf_2026'
ON CONFLICT (campaign_id, locale) DO NOTHING;
