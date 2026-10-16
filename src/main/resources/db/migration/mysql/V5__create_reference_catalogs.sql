CREATE TABLE tb_catalog_entry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    kind VARCHAR(16) NOT NULL,
    name VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    merged_into_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_catalog_merge FOREIGN KEY (merged_into_id) REFERENCES tb_catalog_entry(id)
);
CREATE INDEX idx_catalog_kind ON tb_catalog_entry(kind, merged_into_id);

CREATE TABLE tb_catalog_alias (
    kind VARCHAR(16) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    entry_id BIGINT NOT NULL,
    PRIMARY KEY (kind, normalized_name),
    CONSTRAINT fk_catalog_alias FOREIGN KEY (entry_id) REFERENCES tb_catalog_entry(id)
);

ALTER TABLE tb_cleanup_event ADD COLUMN beach_id BIGINT;
ALTER TABLE tb_cleanup_event ADD CONSTRAINT fk_event_beach FOREIGN KEY (beach_id) REFERENCES tb_catalog_entry(id);
CREATE INDEX idx_event_beach ON tb_cleanup_event(beach_id);
ALTER TABLE tb_trash_collection ADD COLUMN beach_id BIGINT;
ALTER TABLE tb_trash_collection ADD COLUMN material_id BIGINT;
ALTER TABLE tb_trash_collection ADD CONSTRAINT fk_collection_beach FOREIGN KEY (beach_id) REFERENCES tb_catalog_entry(id);
ALTER TABLE tb_trash_collection ADD CONSTRAINT fk_collection_material FOREIGN KEY (material_id) REFERENCES tb_catalog_entry(id);
CREATE INDEX idx_collection_beach ON tb_trash_collection(beach_id);
CREATE INDEX idx_collection_material ON tb_trash_collection(material_id);
