-- V18: Promo code campaign module tables
-- Creates all tables for the universal multi-partner promo campaign module

-- 1. promo_partner
CREATE TABLE IF NOT EXISTS promo_partner (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(60) NOT NULL UNIQUE,
    logo_url TEXT,
    brand_color CHAR(7),
    contact_name VARCHAR,
    contact_email VARCHAR,
    agreement_ref VARCHAR,
    retention_days INT DEFAULT 90 CHECK (retention_days BETWEEN 1 AND 730),
    default_prefixes VARCHAR[],
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_promo_partner_status ON promo_partner(status);

-- 2. promo_campaign
CREATE TABLE IF NOT EXISTS promo_campaign (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id UUID REFERENCES promo_partner(id),
    name VARCHAR(120) NOT NULL,
    code VARCHAR(16) NOT NULL,
    code_normalized VARCHAR(16) NOT NULL UNIQUE,
    discount_type VARCHAR(16) NOT NULL CHECK (discount_type IN ('PERCENT', 'FIXED')),
    discount_value NUMERIC(10,2) NOT NULL CHECK (
        (discount_type = 'PERCENT' AND discount_value BETWEEN 1 AND 100) OR
        (discount_type = 'FIXED' AND discount_value > 0)
    ),
    max_discount_amount NUMERIC(10,2),
    audience_type VARCHAR(32) NOT NULL CHECK (audience_type IN ('UPLOADED_LIST', 'NEW_USERS', 'ALL_USERS')),
    identifier_type VARCHAR(16) NOT NULL DEFAULT 'PHONE' CHECK (identifier_type IN ('PHONE')),
    allowed_prefixes VARCHAR[],
    plan_ids UUID[],
    tier_codes VARCHAR[],
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    uses_per_user INT NOT NULL DEFAULT 1 CHECK (uses_per_user >= 1),
    total_limit INT,
    redeemed_count INT NOT NULL DEFAULT 0,
    first_payment_only BOOLEAN NOT NULL DEFAULT true,
    allow_fitcoin BOOLEAN NOT NULL DEFAULT true,
    allow_installments BOOLEAN NOT NULL DEFAULT true,
    include_existing_users BOOLEAN NOT NULL DEFAULT true,
    priority INT NOT NULL DEFAULT 0,
    brand_color_override CHAR(7),
    logo_url_override TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED')),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_promo_campaign_code_normalized ON promo_campaign(code_normalized);
CREATE INDEX IF NOT EXISTS idx_promo_campaign_status_starts_at ON promo_campaign(status, starts_at);
CREATE INDEX IF NOT EXISTS idx_promo_campaign_status_ends_at ON promo_campaign(status, ends_at);
CREATE INDEX IF NOT EXISTS idx_promo_campaign_partner_id ON promo_campaign(partner_id);
CREATE INDEX IF NOT EXISTS idx_promo_campaign_status ON promo_campaign(status);

-- 3. promo_campaign_translation
CREATE TABLE IF NOT EXISTS promo_campaign_translation (
    campaign_id UUID NOT NULL REFERENCES promo_campaign(id) ON DELETE CASCADE,
    locale VARCHAR(8) NOT NULL CHECK (locale IN ('az', 'en', 'ru')),
    popup_title VARCHAR(40) NOT NULL,
    popup_body VARCHAR(180) NOT NULL,
    cta_label VARCHAR(24) DEFAULT 'Abunelik sec',
    card_label VARCHAR(24),
    not_eligible_text VARCHAR(120),
    coupon_label VARCHAR(24),
    PRIMARY KEY (campaign_id, locale)
);

-- 4. promo_campaign_popup
CREATE TABLE IF NOT EXISTS promo_campaign_popup (
    campaign_id UUID PRIMARY KEY REFERENCES promo_campaign(id) ON DELETE CASCADE,
    cta_target VARCHAR(16) NOT NULL CHECK (cta_target IN ('PLANS', 'PLAN_DETAIL', 'URL')),
    plan_id UUID,
    url TEXT,
    image_url TEXT NOT NULL
);

-- 5. promo_audience_upload
CREATE TABLE IF NOT EXISTS promo_audience_upload (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES promo_campaign(id) ON DELETE CASCADE,
    file_name VARCHAR NOT NULL,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('REPLACE', 'APPEND')),
    total_rows INT NOT NULL DEFAULT 0,
    valid INT NOT NULL DEFAULT 0,
    duplicates INT NOT NULL DEFAULT 0,
    invalid INT NOT NULL DEFAULT 0,
    existing_users INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' CHECK (status IN ('PROCESSING', 'DONE', 'FAILED')),
    report_url TEXT,
    report_csv TEXT,
    uploaded_by BIGINT NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_promo_audience_upload_campaign_id ON promo_audience_upload(campaign_id, uploaded_at DESC);

-- 6. promo_audience_number
CREATE TABLE IF NOT EXISTS promo_audience_number (
    campaign_id UUID NOT NULL REFERENCES promo_campaign(id) ON DELETE CASCADE,
    phone_hash CHAR(64) NOT NULL,
    phone_encrypted BYTEA,
    upload_id UUID NOT NULL REFERENCES promo_audience_upload(id) ON DELETE CASCADE,
    PRIMARY KEY (campaign_id, phone_hash)
);

CREATE INDEX IF NOT EXISTS idx_promo_audience_number_phone_hash ON promo_audience_number(phone_hash);

-- 7. promo_grant
CREATE TABLE IF NOT EXISTS promo_grant (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES promo_campaign(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    phone_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'GRANTED' CHECK (status IN ('GRANTED', 'RESERVED', 'REDEEMED', 'REVOKED', 'EXPIRED')),
    popup_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (popup_status IN ('PENDING', 'SEEN')),
    popup_seen_at TIMESTAMPTZ,
    reserved_order_id VARCHAR(128),
    reserved_until TIMESTAMPTZ,
    redeemed_order_id VARCHAR(128),
    payment_id VARCHAR(128),
    base_amount NUMERIC(10,2),
    discount_amount NUMERIC(10,2),
    final_amount NUMERIC(10,2),
    redeemed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_promo_grant_campaign_user ON promo_grant(campaign_id, user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_promo_grant_campaign_phone_redeemed ON promo_grant(campaign_id, phone_hash) WHERE status = 'REDEEMED';
CREATE INDEX IF NOT EXISTS idx_promo_grant_user_popup ON promo_grant(user_id, popup_status, created_at);
CREATE INDEX IF NOT EXISTS idx_promo_grant_status_reserved_until ON promo_grant(status, reserved_until);

-- 8. Add promo columns to orders table
ALTER TABLE orders ADD COLUMN IF NOT EXISTS promo_campaign_id UUID;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS promo_code VARCHAR(16);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS promo_discount_amount NUMERIC(10,2);

CREATE INDEX IF NOT EXISTS idx_orders_promo_campaign_id ON orders(promo_campaign_id);
CREATE INDEX IF NOT EXISTS idx_orders_promo_code ON orders(promo_code);

-- 9. promo_audit_log (12.2): every write / publish / status change / upload / PII reveal / export
CREATE TABLE IF NOT EXISTS promo_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id UUID,
    before_diff JSONB,
    after_diff JSONB,
    ip VARCHAR(64),
    user_agent VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_promo_audit_log_created_at ON promo_audit_log(created_at);
CREATE INDEX IF NOT EXISTS idx_promo_audit_log_entity ON promo_audit_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_promo_audit_log_admin ON promo_audit_log(admin_id);