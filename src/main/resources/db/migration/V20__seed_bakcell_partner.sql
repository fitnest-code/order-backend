-- V20: Seed Bakcell partner (first promo partner, idempotent).
-- Campaign itself is created via admin wizard (needs plans + audience upload).

INSERT INTO promo_partner (id, name, logo_url, brand_color, contact_name, contact_email,
                           agreement_ref, retention_days, default_prefixes, status)
VALUES ('11111111-1111-1111-1111-111111111111',
        'Bakcell',
        'https://cdn.fitnest.az/partners/bakcell.svg',
        '#E4002B',
        NULL, NULL,
        'BAK-2026-001',
        90,
        ARRAY['55','99'],
        'ACTIVE')
ON CONFLICT (name) DO UPDATE SET
    logo_url = EXCLUDED.logo_url,
    brand_color = EXCLUDED.brand_color,
    agreement_ref = EXCLUDED.agreement_ref,
    retention_days = EXCLUDED.retention_days,
    default_prefixes = EXCLUDED.default_prefixes,
    updated_at = now();
