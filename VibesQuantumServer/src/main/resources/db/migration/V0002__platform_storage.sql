CREATE TABLE IF NOT EXISTS document_store (
  store_key VARCHAR(200) PRIMARY KEY,
  json_value TEXT NOT NULL,
  updated_at_ms BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS conversations (
  conversation_id VARCHAR(80) PRIMARY KEY,
  user_id VARCHAR(160) NOT NULL,
  app_id VARCHAR(100) NOT NULL,
  title VARCHAR(240) NOT NULL,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  last_sequence BIGINT NOT NULL DEFAULT 0,
  archived_at_ms BIGINT
);
CREATE INDEX IF NOT EXISTS idx_conversations_user_updated ON conversations(user_id, updated_at_ms);

CREATE TABLE IF NOT EXISTS conversation_messages (
  conversation_id VARCHAR(80) NOT NULL,
  sequence_no BIGINT NOT NULL,
  message_id VARCHAR(100) NOT NULL,
  user_id VARCHAR(160) NOT NULL,
  client_message_id VARCHAR(100),
  role VARCHAR(24) NOT NULL,
  content TEXT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY(conversation_id, sequence_no),
  UNIQUE(message_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_conversation_client_message ON conversation_messages(user_id, client_message_id);
CREATE INDEX IF NOT EXISTS idx_conversation_messages_user ON conversation_messages(user_id, conversation_id, sequence_no);

CREATE TABLE IF NOT EXISTS conversation_device_cursors (
  user_id VARCHAR(160) NOT NULL,
  device_id VARCHAR(120) NOT NULL,
  conversation_id VARCHAR(80) NOT NULL,
  acknowledged_sequence BIGINT NOT NULL DEFAULT 0,
  updated_at_ms BIGINT NOT NULL,
  PRIMARY KEY(user_id, device_id, conversation_id)
);

CREATE TABLE IF NOT EXISTS subscriptions (
  user_id VARCHAR(160) PRIMARY KEY,
  tier VARCHAR(24) NOT NULL DEFAULT 'FREE',
  product_id VARCHAR(160),
  purchase_token_hash VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'FREE',
  expires_at_ms BIGINT,
  updated_at_ms BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_subscriptions_expiry ON subscriptions(status, expires_at_ms);

CREATE TABLE IF NOT EXISTS billing_purchases (
  purchase_token_hash VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(160) NOT NULL,
  package_name VARCHAR(180) NOT NULL,
  product_id VARCHAR(160) NOT NULL,
  tier VARCHAR(24) NOT NULL,
  purchase_state VARCHAR(32) NOT NULL,
  expires_at_ms BIGINT,
  verified_at_ms BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_billing_purchases_user ON billing_purchases(user_id, verified_at_ms);

CREATE TABLE IF NOT EXISTS cloud_spend_daily (
  user_id VARCHAR(160) NOT NULL,
  utc_day VARCHAR(10) NOT NULL,
  reserved_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
  spent_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
  llm_calls BIGINT NOT NULL DEFAULT 0,
  audio_calls BIGINT NOT NULL DEFAULT 0,
  tokens BIGINT NOT NULL DEFAULT 0,
  updated_at_ms BIGINT NOT NULL,
  PRIMARY KEY(user_id, utc_day)
);
CREATE TABLE IF NOT EXISTS cloud_spend_events (
  event_id VARCHAR(80) PRIMARY KEY,
  user_id VARCHAR(160) NOT NULL,
  utc_day VARCHAR(10) NOT NULL,
  service VARCHAR(24) NOT NULL,
  provider VARCHAR(120),
  estimated_usd DOUBLE PRECISION NOT NULL,
  actual_usd DOUBLE PRECISION NOT NULL,
  token_count BIGINT NOT NULL DEFAULT 0,
  created_at_ms BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cloud_spend_user_day ON cloud_spend_events(user_id, utc_day);

CREATE TABLE IF NOT EXISTS gateway_usage_daily (
  app_id VARCHAR(160) NOT NULL,
  utc_day VARCHAR(10) NOT NULL,
  requests BIGINT NOT NULL DEFAULT 0,
  successful BIGINT NOT NULL DEFAULT 0,
  failed BIGINT NOT NULL DEFAULT 0,
  prompt_tokens BIGINT NOT NULL DEFAULT 0,
  completion_tokens BIGINT NOT NULL DEFAULT 0,
  last_request_at VARCHAR(40),
  PRIMARY KEY(app_id, utc_day)
);
