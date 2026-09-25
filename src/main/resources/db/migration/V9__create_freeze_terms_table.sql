-- ===================================================
--  V9: Create Freeze Terms & Rules Table
-- ===================================================

CREATE TABLE IF NOT EXISTS freeze_terms (
    id              BIGSERIAL PRIMARY KEY,
    html_content_az TEXT NOT NULL DEFAULT '',
    html_content_en TEXT NOT NULL DEFAULT '',
    html_content_ru TEXT NOT NULL DEFAULT '',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
