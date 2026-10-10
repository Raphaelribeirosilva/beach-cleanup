CREATE INDEX idx_collection_user ON tb_trash_collection(volunteer_id);
CREATE INDEX idx_collection_event ON tb_trash_collection(event_id);
CREATE INDEX idx_collection_date ON tb_trash_collection(registration_date);
CREATE INDEX idx_event_date ON tb_cleanup_event(date_time);
