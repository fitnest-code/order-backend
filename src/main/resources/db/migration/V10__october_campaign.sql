CREATE TABLE campaigns (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL UNIQUE,
    status          VARCHAR(16)  NOT NULL,
    start_at        TIMESTAMP    NOT NULL,
    end_at          TIMESTAMP    NOT NULL,
    banner_image_url TEXT,
    cta_target      VARCHAR(32)  NOT NULL DEFAULT 'CAMPAIGN_DETAIL',
    one_per_user    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE campaign_offers (
    id                     BIGSERIAL PRIMARY KEY,
    campaign_id            BIGINT      NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    base_duration_months   INTEGER     NOT NULL,
    bonus_months           INTEGER     NOT NULL,
    UNIQUE (campaign_id, base_duration_months)
);

CREATE TABLE campaign_terms (
    id           BIGSERIAL PRIMARY KEY,
    campaign_id  BIGINT   NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    sort_order   INTEGER  NOT NULL,
    is_positive  BOOLEAN  NOT NULL,
    UNIQUE (campaign_id, sort_order)
);

CREATE TABLE user_campaign_redemptions (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT    NOT NULL,
    campaign_id      BIGINT    NOT NULL REFERENCES campaigns(id),
    subscription_id  BIGINT    NOT NULL,
    payment_order_id VARCHAR(128),
    redeemed_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, campaign_id)
);

CREATE TABLE campaign_impressions (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    campaign_id   BIGINT      NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    context       VARCHAR(16) NOT NULL,
    last_shown_at TIMESTAMP   NOT NULL,
    UNIQUE (user_id, campaign_id, context)
);

ALTER TABLE subscriptions
    ADD COLUMN paid_duration_months INTEGER,
    ADD COLUMN bonus_months         INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN campaign_id          BIGINT,
    ADD COLUMN paid_until           TIMESTAMP,
    ADD COLUMN campaign_banner_dismissed_at TIMESTAMP;

UPDATE subscriptions
   SET bonus_months = 0,
       paid_until = end_at
 WHERE paid_until IS NULL;
