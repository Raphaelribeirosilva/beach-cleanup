package com.raphael.beachcleanup.entities;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "tb_refresh_token")
public class RefreshToken {
    @Id @Column(length = 64) private String tokenHash;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AuthSession session;
    @Column(nullable = false) private boolean consumed;
    protected RefreshToken() {}
    public RefreshToken(String tokenHash, AuthSession session) { this.tokenHash = tokenHash; this.session = session; }
    public AuthSession getSession() { return session; }
    public boolean isConsumed() { return consumed; }
    public void consume() { consumed = true; }
}
