-- ===================================================
--  V13: Refactor Freeze Terms to use Translations Table
-- ===================================================

-- 1. Rename html_content_az to html_content (base AZ content)
ALTER TABLE freeze_terms RENAME COLUMN html_content_az TO html_content;

-- 2. Drop the other language columns (translations go to translations table)
ALTER TABLE freeze_terms DROP COLUMN IF EXISTS html_content_en;
ALTER TABLE freeze_terms DROP COLUMN IF EXISTS html_content_ru;

-- 3. Migrate existing data to translations table
INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
SELECT 'FREEZE_TERMS', id::text, 'EN', 'html_content', html_content_en
FROM freeze_terms WHERE html_content_en IS NOT NULL AND html_content_en != '';

INSERT INTO translations (entity_type, entity_id, language_code, field_name, field_value)
SELECT 'FREEZE_TERMS', id::text, 'RU', 'html_content', html_content_ru
FROM freeze_terms WHERE html_content_ru IS NOT NULL AND html_content_ru != '';