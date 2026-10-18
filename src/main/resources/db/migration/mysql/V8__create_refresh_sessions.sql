CREATE TABLE tb_auth_session (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    credential_version BIGINT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_auth_session_user (user_id),
    INDEX idx_auth_session_expiry (expires_at),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES tb_user(id) ON DELETE CASCADE
);

CREATE TABLE tb_refresh_token (
    token_hash VARCHAR(64) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    consumed BOOLEAN NOT NULL,
    PRIMARY KEY (token_hash),
    INDEX idx_refresh_token_session (session_id),
    CONSTRAINT fk_refresh_token_session FOREIGN KEY (session_id) REFERENCES tb_auth_session(id) ON DELETE CASCADE
);
