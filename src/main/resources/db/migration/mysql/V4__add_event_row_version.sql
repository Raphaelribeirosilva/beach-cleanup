ALTER TABLE tb_cleanup_event
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
