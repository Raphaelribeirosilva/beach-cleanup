ALTER TABLE tb_cleanup_event ADD COLUMN description VARCHAR(2000);

-- One durable batch marker; locking it serializes concurrent application starts.
CREATE TABLE tb_demo_data (
    seed_key VARCHAR(64) NOT NULL PRIMARY KEY,
    completed BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO tb_demo_data (seed_key, completed) VALUES ('coastal-events-v1', FALSE);
