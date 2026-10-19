ALTER TABLE tb_catalog_entry ADD COLUMN state VARCHAR(2) NULL;
ALTER TABLE tb_catalog_entry ADD COLUMN city VARCHAR(255) NULL;
CREATE INDEX ix_catalog_geography ON tb_catalog_entry (state, city);
