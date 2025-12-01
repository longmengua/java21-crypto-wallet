CREATE TABLE deposits (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tx_hash VARCHAR(100) NOT NULL UNIQUE,
  user_address VARCHAR(100),         -- optional: which user/which deposit address
  monitored_address VARCHAR(100) NOT NULL,
  chain VARCHAR(50) NOT NULL,
  token_address VARCHAR(100),        -- NULL = native coin
  amount DECIMAL(38,18) NOT NULL,
  decimals INT DEFAULT 18,
  tx_block BIGINT,
  status VARCHAR(20) NOT NULL,       -- UNCONFIRMED / CONFIRMING / CONFIRMED
  confirmations INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
