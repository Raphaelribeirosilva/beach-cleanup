-- Read-only. Run with the SAME host/port/user/database as the application's mysql profile.
-- No data, history or server settings are modified. Do not paste credentials into logs.
SELECT VERSION() AS server_version, @@version_comment AS server_vendor,
       @@hostname AS server_host, @@port AS server_port, DATABASE() AS current_database;
SHOW SESSION VARIABLES LIKE 'explicit_defaults_for_timestamp';
SHOW GLOBAL VARIABLES LIKE 'explicit_defaults_for_timestamp';
SHOW SESSION VARIABLES LIKE 'sql_mode';

SELECT installed_rank, version, description, type, script, checksum, installed_on, success
FROM flyway_schema_history ORDER BY installed_rank;

SELECT table_name, engine
FROM information_schema.tables
WHERE table_schema=DATABASE()
  AND table_name IN ('tb_auth_session','tb_refresh_token','tb_account_action_token',
                    'tb_auth_rate_limit','tb_audit_entry');

SELECT table_name, column_name, column_type, is_nullable, column_default, extra
FROM information_schema.columns
WHERE table_schema=DATABASE()
  AND table_name IN ('tb_auth_session','tb_refresh_token','tb_account_action_token',
                    'tb_auth_rate_limit','tb_audit_entry')
ORDER BY table_name, ordinal_position;

-- Only if the corresponding tables exist, run individually before considering repair:
-- SHOW CREATE TABLE tb_auth_session;
-- SELECT COUNT(*) AS sessions FROM tb_auth_session;
-- SHOW CREATE TABLE tb_refresh_token;
-- SELECT COUNT(*) AS refresh_tokens FROM tb_refresh_token;
