-- V16: October campaign confirm-body rewording.
-- Product feedback (2026-10-02): banner body changes from
-- "Oktyabr kampaniyasından abunəliyiniz {bonusMonths} ay uzadılacaq" to
-- "Kampaniya üzrə abunəlik müddətinə {bonusMonths} ay əlavə edildi",
-- with matching EN/RU. Idempotent.
-- NOTE: V15 is taken in production by the price-discount restore hotfix, so this
-- change ships as V16 to keep Flyway history linear across branches.

INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
VALUES
    ('CAMPAIGN', '1', 'AZ', 'confirmBody', 'Kampaniya üzrə abunəlik müddətinə {bonusMonths} ay əlavə edildi'),
    ('CAMPAIGN', '1', 'EN', 'confirmBody', '{bonusMonths} months have been added to your subscription period under the campaign.'),
    ('CAMPAIGN', '1', 'RU', 'confirmBody', 'В рамках акции к вашей подписке добавлено {bonusMonths} месяца.')
ON CONFLICT (entity_type, entity_id, field_name, language_code)
DO UPDATE SET field_value = EXCLUDED.field_value;
