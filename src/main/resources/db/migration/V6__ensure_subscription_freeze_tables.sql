-- ===================================================
--  V6: Ensure FitNest Subscription Freeze Tables Exist
-- ===================================================

-- 0. Version column on subscriptions
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 1. Freeze Entitlements
CREATE TABLE IF NOT EXISTS subscription_freeze_entitlements (
    id                  BIGSERIAL PRIMARY KEY,
    subscription_id     BIGINT      NOT NULL,
    user_id             BIGINT      NOT NULL,
    total_days          INT         NOT NULL,
    consumed_days       INT         NOT NULL DEFAULT 0,
    reserved_days       INT         NOT NULL DEFAULT 0,
    policy_version      VARCHAR(20) NOT NULL,
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_entitlement_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(subscription_id),
    CONSTRAINT uq_entitlement_subscription UNIQUE (subscription_id),
    CONSTRAINT chk_entitlement_non_negative
        CHECK (total_days >= 0 AND consumed_days >= 0 AND reserved_days >= 0),
    CONSTRAINT chk_entitlement_budget
        CHECK (consumed_days + reserved_days <= total_days)
);

CREATE INDEX IF NOT EXISTS idx_entitlement_user_id     ON subscription_freeze_entitlements(user_id);
CREATE INDEX IF NOT EXISTS idx_entitlement_sub_id      ON subscription_freeze_entitlements(subscription_id);

-- 2. Subscription Freezes
CREATE TABLE IF NOT EXISTS subscription_freezes (
    id                  BIGSERIAL   PRIMARY KEY,
    subscription_id     BIGINT      NOT NULL,
    user_id             BIGINT      NOT NULL,
    requested_days      INT         NOT NULL,
    used_days           INT,
    returned_days       INT,
    start_at            TIMESTAMP   NOT NULL,
    plan_end_at         TIMESTAMP   NOT NULL,
    actual_end_at       TIMESTAMP,
    expiry_before       TIMESTAMP   NOT NULL,
    expiry_after        TIMESTAMP,
    status              VARCHAR(20) NOT NULL,
    ended_by            VARCHAR(30),
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_freeze_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(subscription_id),
    CONSTRAINT chk_freeze_status
        CHECK (status IN ('ACTIVE','COMPLETED','ENDED_EARLY','TERMINATED')),
    CONSTRAINT chk_freeze_days_positive
        CHECK (requested_days >= 1)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_one_active_freeze_per_sub
    ON subscription_freezes(subscription_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_freeze_sub_id           ON subscription_freezes(subscription_id);
CREATE INDEX IF NOT EXISTS idx_freeze_user_id          ON subscription_freezes(user_id);
CREATE INDEX IF NOT EXISTS idx_freeze_status           ON subscription_freezes(status);
CREATE INDEX IF NOT EXISTS idx_freeze_plan_end_at      ON subscription_freezes(plan_end_at) WHERE status = 'ACTIVE';

-- 3. Freeze Preview Cache
CREATE TABLE IF NOT EXISTS freeze_previews (
    id              BIGSERIAL   PRIMARY KEY,
    subscription_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    requested_days  INT         NOT NULL,
    plan_end_at     TIMESTAMP   NOT NULL,
    expiry_after    TIMESTAMP   NOT NULL,
    expected_version BIGINT     NOT NULL,
    expires_at      TIMESTAMP   NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_preview_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(subscription_id)
);

CREATE INDEX IF NOT EXISTS idx_preview_sub_user  ON freeze_previews(subscription_id, user_id);
CREATE INDEX IF NOT EXISTS idx_preview_expires   ON freeze_previews(expires_at);

-- 4. Idempotency Records
CREATE TABLE IF NOT EXISTS freeze_idempotency_records (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    endpoint        VARCHAR(60)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_body   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_idempotency UNIQUE (user_id, endpoint, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON freeze_idempotency_records(expires_at);

-- 5. Freeze Audit Events
CREATE TABLE IF NOT EXISTS freeze_audit_events (
    id              BIGSERIAL   PRIMARY KEY,
    freeze_id       BIGINT,
    subscription_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    event_type      VARCHAR(40) NOT NULL,
    payload         JSONB,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_freeze_id  ON freeze_audit_events(freeze_id);
CREATE INDEX IF NOT EXISTS idx_audit_user_id    ON freeze_audit_events(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_sub_id     ON freeze_audit_events(subscription_id);

-- 6. Freeze Outbox Events
CREATE TABLE IF NOT EXISTS freeze_outbox_events (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type  VARCHAR(40) NOT NULL,
    user_id     BIGINT      NOT NULL,
    freeze_id   BIGINT,
    payload     JSONB       NOT NULL,
    published   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON freeze_outbox_events(published, created_at)
    WHERE published = FALSE;
