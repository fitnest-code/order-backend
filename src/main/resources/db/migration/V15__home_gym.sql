-- ===================================================
--  V15: Home Gym (Əsas zal / unlimited access) — spec v2
-- ===================================================

-- 1. Subscription entry mode (HOME_GYM_PENDING -> HOME_GYM -> STANDARD)
ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS entry_mode VARCHAR(30) NOT NULL DEFAULT 'HOME_GYM_PENDING';

-- Existing subscriptions that never had a home gym start in pending mode.
UPDATE subscriptions SET entry_mode = 'HOME_GYM_PENDING' WHERE entry_mode IS NULL;

-- 2. Visit snapshots (home-gym counters + per-visit pricing, R14–R17)
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS entry_type VARCHAR(30);
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS home_gym_id BIGINT;
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS period_start DATE;
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS time_slot_rule_id BIGINT;
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS price_package VARCHAR(30);
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS single_price NUMERIC(10, 2);
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS unlimited_price NUMERIC(10, 2);
-- Frozen/reservation visits do not consume the monthly limit (legacy consumeFrozen path).
ALTER TABLE gym_visits ADD COLUMN IF NOT EXISTS counts_towards_limit BOOLEAN NOT NULL DEFAULT TRUE;

-- Legacy visits are standard visits. Their period_start must be the start of the
-- subscription month containing the visit (subscription start day, clamped to the
-- month length), not the calendar day, otherwise current-month counters would miss them.
UPDATE gym_visits SET entry_type = 'STANDARD' WHERE entry_type IS NULL;

UPDATE gym_visits v
SET period_start = calc.period_start
FROM (
    SELECT gv.id,
           CASE
               WHEN gv.checked_in_at::date < s.start_at::date THEN s.start_at::date
               WHEN candidate.this_month <= gv.checked_in_at::date THEN candidate.this_month
               ELSE candidate.previous_month
           END AS period_start
    FROM gym_visits gv
    JOIN subscriptions s ON s.subscription_id = gv.subscription_id
    CROSS JOIN LATERAL (
        SELECT make_date(
                   EXTRACT(YEAR FROM gv.checked_in_at)::int,
                   EXTRACT(MONTH FROM gv.checked_in_at)::int,
                   LEAST(
                       EXTRACT(DAY FROM s.start_at)::int,
                       EXTRACT(DAY FROM (date_trunc('month', gv.checked_in_at)
                           + INTERVAL '1 month' - INTERVAL '1 day'))::int
                   )
               ) AS this_month,
               make_date(
                   EXTRACT(YEAR FROM (date_trunc('month', gv.checked_in_at) - INTERVAL '1 month'))::int,
                   EXTRACT(MONTH FROM (date_trunc('month', gv.checked_in_at) - INTERVAL '1 month'))::int,
                   LEAST(
                       EXTRACT(DAY FROM s.start_at)::int,
                       EXTRACT(DAY FROM (date_trunc('month', gv.checked_in_at) - INTERVAL '1 day'))::int
                   )
               ) AS previous_month
    ) candidate
    WHERE gv.period_start IS NULL
) calc
WHERE v.id = calc.id;

-- Visits without a matching subscription (defensive) fall back to their calendar day.
UPDATE gym_visits SET period_start = CAST(checked_in_at AS DATE) WHERE period_start IS NULL;

CREATE INDEX IF NOT EXISTS idx_gym_visits_sub_period
    ON gym_visits (subscription_id, period_start);
CREATE INDEX IF NOT EXISTS idx_gym_visits_user_gym_period
    ON gym_visits (user_id, gym_id, period_start);

-- 3. Home gym selection history. One ACTIVE row per subscription (partial unique index).
CREATE TABLE IF NOT EXISTS subscription_home_gym (
    id                   BIGSERIAL PRIMARY KEY,
    subscription_id      BIGINT      NOT NULL,
    user_id              BIGINT      NOT NULL,
    gym_id               BIGINT      NOT NULL,
    status               VARCHAR(30) NOT NULL,
    source               VARCHAR(30) NOT NULL,
    selected_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    ended_at             TIMESTAMP,
    end_reason           VARCHAR(40),
    ends_at              TIMESTAMP,
    end_reason_visit_id  BIGINT,
    version              BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT fk_home_gym_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(subscription_id),
    CONSTRAINT chk_home_gym_status
        CHECK (status IN ('ACTIVE', 'REMOVED_BY_SWITCH', 'CHANGED', 'ENDED')),
    CONSTRAINT chk_home_gym_source
        CHECK (source IN ('POST_PAYMENT', 'SUBSCRIPTION_SCREEN', 'FIRST_QR', 'ADMIN')),
    CONSTRAINT chk_home_gym_end_reason
        CHECK (end_reason IS NULL OR end_reason IN
               ('SWITCH', 'CHANGE', 'UPGRADE_NOT_IN_PACKAGE', 'GYM_HOME_DISABLED', 'SUBSCRIPTION_ENDED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_home_gym_active_per_subscription
    ON subscription_home_gym (subscription_id) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_home_gym_subscription
    ON subscription_home_gym (subscription_id);
CREATE INDEX IF NOT EXISTS idx_home_gym_gym_status
    ON subscription_home_gym (gym_id, status);
CREATE INDEX IF NOT EXISTS idx_home_gym_user
    ON subscription_home_gym (user_id);

-- 4. Single-use QR confirmation tokens (R5 switching). TTL 5 minutes.
CREATE TABLE IF NOT EXISTS home_gym_confirmation_token (
    id                BIGSERIAL PRIMARY KEY,
    token             VARCHAR(64) NOT NULL,
    user_id           BIGINT      NOT NULL,
    subscription_id   BIGINT      NOT NULL,
    target_gym_id     BIGINT      NOT NULL,
    confirmation_type VARCHAR(40) NOT NULL,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMP   NOT NULL,
    used_at           TIMESTAMP,
    CONSTRAINT uq_home_gym_confirmation_token UNIQUE (token)
);

CREATE INDEX IF NOT EXISTS idx_home_gym_token_lookup
    ON home_gym_confirmation_token (user_id, subscription_id, target_gym_id, used_at);

-- 5. Monthly gym settlement lines (R15–R17).
CREATE TABLE IF NOT EXISTS gym_settlement_line (
    id              BIGSERIAL PRIMARY KEY,
    run_month       VARCHAR(7)    NOT NULL,
    gym_id          BIGINT        NOT NULL,
    subscription_id BIGINT        NOT NULL,
    period_start    DATE          NOT NULL,
    period_end      DATE          NOT NULL,
    visits          INT           NOT NULL DEFAULT 0,
    home_visits     INT           NOT NULL DEFAULT 0,
    mode            VARCHAR(20)   NOT NULL,
    price_package   VARCHAR(30),
    amount          NUMERIC(10, 2) NOT NULL DEFAULT 0,
    breakdown       JSONB,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_settlement_mode CHECK (mode IN ('PER_VISIT', 'UNLIMITED')),
    CONSTRAINT uq_settlement_line UNIQUE (gym_id, subscription_id, period_start)
);

CREATE INDEX IF NOT EXISTS idx_settlement_run_month
    ON gym_settlement_line (run_month);
CREATE INDEX IF NOT EXISTS idx_settlement_gym
    ON gym_settlement_line (gym_id, run_month);

-- 6. R18 disable campaign captured from the admin pop-up.
CREATE TABLE IF NOT EXISTS home_gym_disable_campaign (
    id          BIGSERIAL PRIMARY KEY,
    gym_id      BIGINT      NOT NULL,
    title       JSONB       NOT NULL,
    body        JSONB       NOT NULL,
    recipients  JSONB,
    created_by  BIGINT,
    disabled_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_home_gym_disable_campaign_gym
    ON home_gym_disable_campaign (gym_id, disabled_at DESC);

-- 7. Idempotency for PUT /subscriptions/{id}/home-gym.
CREATE TABLE IF NOT EXISTS home_gym_idempotency (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    user_id         BIGINT       NOT NULL,
    subscription_id BIGINT       NOT NULL,
    request_hash    VARCHAR(128) NOT NULL,
    response_json   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_home_gym_idempotency_key UNIQUE (idempotency_key)
);
