package com.raphael.beachcleanup.entities;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Server-side session family. No plaintext credentials are persisted. */
@Entity
@Table(name = "tb_auth_session")
public class AuthSession {
    @Id @Column(length = 36) private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;
    @Column(nullable = false) private long credentialVersion;
    @Column(nullable = false) private Instant expiresAt;
    private Instant revokedAt;

    protected AuthSession() {}
    public AuthSession(String id, User user, Instant expiresAt) {
        this.id = id; this.user = user; this.expiresAt = expiresAt;
        this.credentialVersion = user.getCredentialVersion();
    }
    public String getId() { return id; }
    public User getUser() { return user; }
    public long getCredentialVersion() { return credentialVersion; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean active(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }
    public void revoke(Instant now) { if (revokedAt == null) revokedAt = now; }
}
