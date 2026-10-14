ALTER TABLE tb_trash_collection
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
