-- Presentation only; no changes to promo grants, subscription prices or October campaigns.
CREATE TABLE IF NOT EXISTS plan_campaigns (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    package_ids BIGINT[],
    duration_months INTEGER[],
    content JSONB NOT NULL DEFAULT '{}',
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ,
    updated_by BIGINT
);

INSERT INTO plan_campaigns (id, code, name, enabled, package_ids, duration_months, content, version, updated_at)
VALUES ('bacc3110-2026-4000-8000-000000000001', 'BAKCELL_PLANS', 'Bakcell plan banneri', FALSE,
        '{}', '{3,6,12}',
        '{"az":{"heading":"Bakcell-ə özəl kampaniya","subtitle":"3, 6 və 12 aylıq planlarda xüsusi təkliflər","specialOffersTitle":"Xüsusi təkliflər","imageAlt":"Bakcell-ə özəl kampaniya"},"en":{"heading":"Exclusive Bakcell campaign","subtitle":"Special offers on 3, 6 and 12 month plans","specialOffersTitle":"Special offers","imageAlt":"Exclusive Bakcell campaign"},"ru":{"heading":"Специальная акция Bakcell","subtitle":"Специальные предложения на планы 3, 6 и 12 месяцев","specialOffersTitle":"Специальные предложения","imageAlt":"Специальная акция Bakcell"}}'::jsonb,
        0, now())
ON CONFLICT (code) DO NOTHING;
