-- ===================================================
--  V13: Refactor Freeze Terms to use Translations Table
-- ===================================================
-- NOTE (2026-10-05 fix): the original version of this migration dropped the
-- EN/RU columns BEFORE copying their content, so it could never run. The order
-- below is the corrected one: copy first, then rename, then drop.
-- Idempotent: safe to re-run (guards + conflict handling).

-- 1. Migrate existing EN/RU content to translations table first
INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
SELECT 'FREEZE_TERMS', id::text, 'EN', 'html_content', html_content_en
FROM freeze_terms WHERE html_content_en IS NOT NULL AND html_content_en != ''
ON CONFLICT (entity_type, entity_id, field_name, language_code) DO NOTHING;

INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
SELECT 'FREEZE_TERMS', id::text, 'RU', 'html_content', html_content_ru
FROM freeze_terms WHERE html_content_ru IS NOT NULL AND html_content_ru != ''
ON CONFLICT (entity_type, entity_id, field_name, language_code) DO NOTHING;

-- 2. Rename html_content_az to html_content (base AZ content), if still present
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'freeze_terms' AND column_name = 'html_content_az'
    ) THEN
        ALTER TABLE freeze_terms RENAME COLUMN html_content_az TO html_content;
    END IF;
END $$;

-- 3. Drop the other language columns (translations go to translations table)
ALTER TABLE freeze_terms DROP COLUMN IF EXISTS html_content_en;
ALTER TABLE freeze_terms DROP COLUMN IF EXISTS html_content_ru;
