package com.raphael.beachcleanup.entities;

import java.time.Instant;
import jakarta.persistence.*;

/** Durable requests only: never persist a message body, password or raw action token. */
@Entity @Table(name = "tb_account_mail_queue", indexes = {
    @Index(name = "ix_account_mail_due", columnList = "status,nextAttemptAt,id"),
    @Index(name = "ix_account_mail_lease", columnList = "status,lockedUntil"),
    @Index(name = "ix_account_mail_retention", columnList = "status,updatedAt")})
public class AccountMailJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String recipient;
    @Column(nullable = false, length = 20) private String purpose;
    // Snapshots deliberately have no FK: deleting an account must remain possible.
    private Long ownerId;
    private Long credentialVersion;
    @Column(nullable = false, length = 16) private String status;
    @Column(nullable = false) private int attempts;
    @Column(nullable = false) private Instant nextAttemptAt;
    private Instant lockedUntil;
    @Column(length = 36) private String claimToken;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Column(length = 64) private String lastError;
    protected AccountMailJob() {}
}
