-- DATETIME has no legacy TIMESTAMP initialization/update behavior. Instants
-- are stored in UTC; convert old TIMESTAMP values using UTC on this connection.
-- Nullable revocation remains TIMESTAMP with an explicit NULL default.
/*!50000 SET @beachcleanup_previous_time_zone = @@SESSION.time_zone */;
/*!50000 SET SESSION time_zone = '+00:00' */;
ALTER TABLE tb_auth_session
    MODIFY COLUMN expires_at DATETIME(6) NOT NULL;
ALTER TABLE tb_auth_session
    MODIFY COLUMN revoked_at TIMESTAMP(6) NULL DEFAULT NULL;

ALTER TABLE tb_account_action_token
    MODIFY COLUMN expires_at DATETIME(6) NOT NULL;

ALTER TABLE tb_auth_rate_limit
    MODIFY COLUMN window_start DATETIME(6) NOT NULL;

ALTER TABLE tb_audit_entry
    MODIFY COLUMN occurred_at DATETIME(6) NOT NULL;
/*!50000 SET SESSION time_zone = @beachcleanup_previous_time_zone */;
