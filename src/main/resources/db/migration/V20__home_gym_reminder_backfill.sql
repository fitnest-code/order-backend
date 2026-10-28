-- ===================================================
--  V20: no retroactive home-gym reminders on rollout
-- ===================================================
--
-- V15 put every existing subscription into HOME_GYM_PENDING and V18 added
-- home_gym_reminder_sent_at as NULL. Without a backfill the first run of the
-- reminder job after deployment would push "select your home gym" to the whole
-- active base at once, although the message is defined as "24h after purchase".
--
-- Rows purchased more than 24h before the deployment are treated as already
-- reminded; only subscriptions bought after the rollout get the reminder.

UPDATE subscriptions
SET home_gym_reminder_sent_at = NOW()
WHERE home_gym_reminder_sent_at IS NULL
  AND start_at < NOW() - INTERVAL '24 hours';
