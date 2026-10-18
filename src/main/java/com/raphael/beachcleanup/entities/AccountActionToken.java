package com.raphael.beachcleanup.entities;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity @Table(name = "tb_account_action_token")
public class AccountActionToken {
    @Id @Column(length = 64) private String tokenHash;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) private User user;
    @Column(nullable = false, length = 20) private String purpose;
    @Column(nullable = false) private String email;
    @Column(nullable = false) private long credentialVersion;
    @Column(nullable = false) private Instant expiresAt;
    @Column(nullable = false) private boolean consumed;
    protected AccountActionToken() {}
    public AccountActionToken(String hash, User user, String purpose, Instant expiry) {
        tokenHash = hash; this.user = user; this.purpose = purpose; email = user.getEmail();
        credentialVersion = user.getCredentialVersion(); expiresAt = expiry;
    }
    public User getUser() { return user; }
    public boolean valid(User user, String expected, Instant now) {
        return !consumed && purpose.equals(expected) && expiresAt.isAfter(now)
            && email.equals(user.getEmail()) && credentialVersion == user.getCredentialVersion();
    }
    public void consume() { consumed = true; }
}
