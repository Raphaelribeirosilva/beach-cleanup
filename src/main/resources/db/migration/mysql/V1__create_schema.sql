CREATE TABLE tb_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255),
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255),
    role ENUM('ADMIN', 'VOLUNTEER') NOT NULL,
    credential_version BIGINT NOT NULL,
    row_version BIGINT,
    total_collected_kg DOUBLE,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_email UNIQUE (email)
);

CREATE TABLE tb_cleanup_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255),
    location VARCHAR(255),
    date_time DATETIME(6),
    status VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE tb_event_volunteer (
    volunteer_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    PRIMARY KEY (volunteer_id, event_id),
    CONSTRAINT fk_registration_user FOREIGN KEY (volunteer_id) REFERENCES tb_user(id),
    CONSTRAINT fk_registration_event FOREIGN KEY (event_id) REFERENCES tb_cleanup_event(id)
);

CREATE TABLE tb_trash_collection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    beach VARCHAR(255),
    material_type VARCHAR(255),
    weight_kg DOUBLE,
    registration_date DATE,
    volunteer_id BIGINT,
    event_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_collection_user FOREIGN KEY (volunteer_id) REFERENCES tb_user(id),
    CONSTRAINT fk_collection_event FOREIGN KEY (event_id) REFERENCES tb_cleanup_event(id)
);
