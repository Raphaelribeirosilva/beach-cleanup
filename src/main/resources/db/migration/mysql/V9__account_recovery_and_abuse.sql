ALTER TABLE tb_user ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE tb_auth_session SET revoked_at = CURRENT_TIMESTAMP(6) WHERE revoked_at IS NULL;

CREATE TABLE tb_account_action_token (
    token_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    email VARCHAR(255) NOT NULL,
    credential_version BIGINT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed BOOLEAN NOT NULL,
    INDEX idx_account_action_user (user_id),
    INDEX idx_account_action_expiry (expires_at),
    CONSTRAINT fk_account_action_user FOREIGN KEY (user_id) REFERENCES tb_user(id) ON DELETE CASCADE
);

CREATE TABLE tb_auth_rate_limit (
    bucket_key VARCHAR(64) NOT NULL PRIMARY KEY,
    window_start TIMESTAMP(6) NOT NULL,
    attempts INT NOT NULL
);
