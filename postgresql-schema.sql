CREATE TABLE IF NOT EXISTS graphicalmatrix_enrollment (
  user_id VARCHAR(255) PRIMARY KEY,
  sequence VARCHAR(1024) NOT NULL,
  initial_sequence VARCHAR(1024) NOT NULL DEFAULT '',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  failed_count INTEGER NOT NULL DEFAULT 0,
  locked_until BIGINT NOT NULL DEFAULT 0,
  mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix',
  totp_seed VARCHAR(255),
  totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED',
  totp_registered_at BIGINT NOT NULL DEFAULT 0,
  last_success_at BIGINT NOT NULL DEFAULT 0,
  force_sequence_change INTEGER NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_status
  ON graphicalmatrix_enrollment (status);

CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_mfa_method
  ON graphicalmatrix_enrollment (mfa_method);

CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_totp_status
  ON graphicalmatrix_enrollment (totp_status);

CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_force_sequence_change
  ON graphicalmatrix_enrollment (force_sequence_change);
