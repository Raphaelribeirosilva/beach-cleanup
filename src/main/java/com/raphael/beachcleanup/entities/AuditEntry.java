package com.raphael.beachcleanup.entities;

import java.time.Instant;
import jakarta.persistence.*;

/** No entity relationships: evidence survives deletion of an otherwise empty resource. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "tb_audit_entry", indexes = {
    @Index(name = "idx_audit_resource", columnList = "resource,resourceId"),
    @Index(name = "idx_audit_time", columnList = "occurredAt,id")
})
public class AuditEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, updatable = false) public Instant occurredAt;
    @Column(updatable = false) public Long actorId;
    @Column(nullable = false, length = 20, updatable = false) public String actorRole;
    @Column(nullable = false, length = 30, updatable = false) public String resource;
    @Column(nullable = false, updatable = false) public Long resourceId;
    @Column(nullable = false, length = 30, updatable = false) public String action;
    @Column(updatable = false) public Long previousUserId;
    @Column(updatable = false) public Long nextUserId;
    @Column(updatable = false) public Long previousEventId;
    @Column(updatable = false) public Long nextEventId;
    @Column(updatable = false) public Long targetId;
    @Column(length = 30, updatable = false) public String previousState;
    @Column(length = 30, updatable = false) public String nextState;
}
