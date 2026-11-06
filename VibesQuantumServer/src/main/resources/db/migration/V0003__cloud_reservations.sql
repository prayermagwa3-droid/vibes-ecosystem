CREATE TABLE IF NOT EXISTS cloud_spend_reservations (
  reservation_id VARCHAR(80) PRIMARY KEY,
  user_id VARCHAR(160) NOT NULL,
  utc_day VARCHAR(10) NOT NULL,
  service VARCHAR(24) NOT NULL,
  estimated_usd DOUBLE PRECISION NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at_ms BIGINT NOT NULL,
  settled_at_ms BIGINT
);
CREATE INDEX IF NOT EXISTS idx_cloud_reservations_user_state ON cloud_spend_reservations(user_id, state, utc_day);
