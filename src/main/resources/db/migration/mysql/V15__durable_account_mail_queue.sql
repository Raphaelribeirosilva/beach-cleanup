CREATE TABLE tb_account_mail_queue (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient VARCHAR(255) NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    owner_id BIGINT NULL,
    credential_version BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    locked_until DATETIME(6) NULL,
    claim_token VARCHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    last_error VARCHAR(64) NULL,
    INDEX ix_account_mail_due (status, next_attempt_at, id),
    INDEX ix_account_mail_lease (status, locked_until),
    INDEX ix_account_mail_retention (status, updated_at)
);
