-- Align bf_2026 landing terms with the BF UI design (1-month excluded, once per user,
-- FitCoin on discounted amount, auto discount). V109 used a different draft; if it already
-- ran on DEV, ON CONFLICT DO NOTHING left the old terms — this UPDATE replaces them.
-- Safe to re-run intent: sets exact design copy for az/en/ru.

UPDATE pricing_campaign_landing_i18n AS l
SET terms_json = v.terms_json,
    updated_at = now()
FROM pricing_campaigns c
CROSS JOIN (VALUES
    ('az', '["Kampaniya {startsAt}–{endsAt} ({endsAt} {endsAtTime}, Bakı vaxtı) keçərlidir.",
             "1 aylıq abunəlik kampaniyaya daxil deyil.",
             "Hər istifadəçi 1 dəfə yararlana bilər.",
             "FitCoin endirimli məbləğə tətbiq olunur.",
             "Endirim ödəniş zamanı avtomatik tətbiq olunur."]'),
    ('en', '["Campaign is valid {startsAt}–{endsAt} ({endsAt} {endsAtTime}, Baku time).",
             "1-month subscriptions are not included.",
             "Each user can use the offer once.",
             "FitCoin is applied to the discounted amount.",
             "The discount is applied automatically at payment."]'),
    ('ru', '["Акция действует {startsAt}–{endsAt} ({endsAt} {endsAtTime}, время Баку).",
             "Месячная подписка в акцию не входит.",
             "Каждый пользователь может воспользоваться один раз.",
             "FitCoin применяется к сумме со скидкой.",
             "Скидка применяется автоматически при оплате."]')
) AS v (locale, terms_json)
WHERE c.code = 'bf_2026'
  AND l.campaign_id = c.id
  AND l.locale = v.locale;
