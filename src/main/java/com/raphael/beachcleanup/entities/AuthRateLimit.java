package com.raphael.beachcleanup.entities;

import java.time.Instant;
import jakarta.persistence.*;

@Entity @Table(name = "tb_auth_rate_limit")
public class AuthRateLimit {
    @Id @Column(length = 64) private String bucketKey;
    @Column(nullable = false) private Instant windowStart;
    @Column(nullable = false) private int attempts;
    protected AuthRateLimit() {}
    public AuthRateLimit(String key, Instant now) { bucketKey = key; windowStart = now; }
    public long consume(Instant now, int maximum, long seconds) {
        if (!windowStart.plusSeconds(seconds).isAfter(now)) { windowStart = now; attempts = 0; }
        if (attempts >= maximum) return Math.max(1, java.time.Duration.between(now, windowStart.plusSeconds(seconds)).getSeconds());
        attempts++; return 0;
    }
}
