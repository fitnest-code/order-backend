CREATE TABLE primary_gym_selections_v4 (
 id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, subscription_id BIGINT NOT NULL REFERENCES subscriptions(subscription_id),
 gym_id BIGINT NOT NULL, gym_name VARCHAR(255) NOT NULL, selected_at TIMESTAMP NOT NULL, ended_at TIMESTAMP,
 source VARCHAR(40) NOT NULL, end_reason VARCHAR(40)
);
CREATE UNIQUE INDEX primary_gym_v4_current ON primary_gym_selections_v4(subscription_id) WHERE ended_at IS NULL;
CREATE INDEX primary_gym_v4_gym ON primary_gym_selections_v4(gym_id) WHERE ended_at IS NULL;
CREATE TABLE gym_visit_pricing_v4 (
 visit_id BIGINT PRIMARY KEY REFERENCES gym_visits(id), user_id BIGINT NOT NULL, request_id VARCHAR(100) NOT NULL,
 subscription_id BIGINT NOT NULL REFERENCES subscriptions(subscription_id), gym_id BIGINT NOT NULL,
 selection_id BIGINT REFERENCES primary_gym_selections_v4(id), checked_in_at TIMESTAMP NOT NULL,
 cycle_start TIMESTAMP NOT NULL, cycle_end TIMESTAMP NOT NULL, primary_visit BOOLEAN NOT NULL,
 general_visits BIGINT NOT NULL, primary_visits BIGINT NOT NULL, entry_price NUMERIC(12,2) NOT NULL,
 monthly_price NUMERIC(12,2), price_tier VARCHAR(20) NOT NULL, policy_version BIGINT NOT NULL,
 UNIQUE(user_id,request_id)
);
CREATE INDEX gym_visit_v4_settlement ON gym_visit_pricing_v4(subscription_id,gym_id,cycle_start);
CREATE INDEX gym_visits_v4_cycle ON gym_visits(subscription_id,checked_in_at);
