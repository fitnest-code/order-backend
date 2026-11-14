-- Black Friday pricing campaigns (v2 pricing API). Deliberately separate from the
-- October bonus-month `campaigns` and from `promo_campaign` (promo codes): those
-- grant extra months / code discounts, this one reprices the list price by duration.

CREATE TABLE IF NOT EXISTS pricing_campaigns (
    id                   UUID PRIMARY KEY,
    code                 VARCHAR(64)  NOT NULL UNIQUE,
    type                 VARCHAR(32)  NOT NULL DEFAULT 'BLACK_FRIDAY',
    status               VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    starts_at            TIMESTAMPTZ  NOT NULL,
    ends_at              TIMESTAMPTZ  NOT NULL,
    max_percent          INT          NOT NULL,
    push_title_az        VARCHAR(255),
    push_title_ru        VARCHAR(255),
    push_title_en        VARCHAR(255),
    push_body_az         TEXT,
    push_body_ru         TEXT,
    push_body_en         TEXT,
    -- "last 12 hours" reminder copy lives next to the start copy so admins edit one row.
    push_last12h_title_az VARCHAR(255),
    push_last12h_title_ru VARCHAR(255),
    push_last12h_title_en VARCHAR(255),
    push_last12h_body_az  TEXT,
    push_last12h_body_ru  TEXT,
    push_last12h_body_en  TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_pricing_campaign_status CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED', 'ENDED'))
);

CREATE TABLE IF NOT EXISTS pricing_campaign_discounts (
    id          BIGSERIAL PRIMARY KEY,
    campaign_id UUID    NOT NULL REFERENCES pricing_campaigns (id) ON DELETE CASCADE,
    months      INT     NOT NULL,
    percent     NUMERIC(5, 2) NOT NULL,
    CONSTRAINT uq_pricing_discount_campaign_months UNIQUE (campaign_id, months)
);

-- One row per (campaign, user): the unique key is what enforces "one redemption per user".
-- A refund intentionally never deletes the row.
CREATE TABLE IF NOT EXISTS pricing_campaign_redemptions (
    id          BIGSERIAL PRIMARY KEY,
    campaign_id UUID         NOT NULL REFERENCES pricing_campaigns (id) ON DELETE CASCADE,
    user_id     BIGINT       NOT NULL,
    order_id    VARCHAR(128),
    redeemed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_pricing_redemption_campaign_user UNIQUE (campaign_id, user_id)
);

ALTER TABLE orders ADD COLUMN IF NOT EXISTS pricing_campaign_id UUID;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS pricing_discount_percent NUMERIC(5, 2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS pricing_discount_amount NUMERIC(10, 2);

-- Seed: bf_2026, Baku time (+04). Idempotent so reruns on restored DBs are safe.
INSERT INTO pricing_campaigns (id, code, type, status, starts_at, ends_at, max_percent,
    push_title_az, push_title_ru, push_title_en,
    push_body_az, push_body_ru, push_body_en,
    push_last12h_title_az, push_last12h_title_ru, push_last12h_title_en,
    push_last12h_body_az, push_last12h_body_ru, push_last12h_body_en)
VALUES (gen_random_uuid(), 'bf_2026', 'BLACK_FRIDAY', 'ACTIVE',
    TIMESTAMPTZ '2026-11-10 00:00:00+04', TIMESTAMPTZ '2026-11-30 23:59:59+04', 30,
    -- Start push (10.11 00:00) — deep link opens campaign story
    'Qara Cümə başladı',
    'Чёрная пятница началась',
    'Black Friday has started',
    '3, 6 və 12 aylıq abunəliklərdə 30%-dək endirim. 30 noyabr 23:59-a qədər keçərlidir.',
    'Скидки до 30% на абонементы на 3, 6 и 12 месяцев. Действует до 30 ноября 23:59.',
    'Up to 30% off on 3, 6 and 12 month memberships. Valid until 30 November 23:59.',
    -- Last 12 hours push (30.11 12:00)
    'Son 12 saat',
    'Последние 12 часов',
    'Last 12 hours',
    '30%-dək endirim bu gecə 23:59-da bitir.',
    'Скидка до 30% заканчивается сегодня в 23:59.',
    'Up to 30% off ends tonight at 23:59.')
ON CONFLICT (code) DO NOTHING;

INSERT INTO pricing_campaign_discounts (campaign_id, months, percent)
SELECT c.id, d.months, d.percent
FROM pricing_campaigns c
CROSS JOIN (VALUES (3, 20.00), (6, 25.00), (12, 30.00)) AS d (months, percent)
WHERE c.code = 'bf_2026'
ON CONFLICT (campaign_id, months) DO NOTHING;
