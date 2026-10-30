-- V17: Freeze episode status display names (AZ source of truth).
-- Product request: ACTIVE shows as "Dondurulub", COMPLETED as "Aktivləşdirildi"
-- (previously confusing Aktiv/Tamamlandı on clients). EN/RU are served from
-- hardcoded maps in TranslationServiceImpl; AZ comes from this table.
-- Idempotent.

INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
VALUES
    ('FREEZE_STATUS', 'ACTIVE', 'AZ', 'name', 'Dondurulub'),
    ('FREEZE_STATUS', 'COMPLETED', 'AZ', 'name', 'Aktivləşdirildi'),
    ('FREEZE_STATUS', 'ENDED_EARLY', 'AZ', 'name', 'Erkən aktivləşdirildi'),
    ('FREEZE_STATUS', 'TERMINATED', 'AZ', 'name', 'Dayandırıldı')
ON CONFLICT (entity_type, entity_id, field_name, language_code)
DO UPDATE SET field_value = EXCLUDED.field_value;
