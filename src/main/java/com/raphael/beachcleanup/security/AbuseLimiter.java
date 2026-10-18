package com.raphael.beachcleanup.security;

import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.*;
import com.raphael.beachcleanup.config.AccountPolicy;
import com.raphael.beachcleanup.entities.AuthRateLimit;
import com.raphael.beachcleanup.repositories.AuthRateLimitRepository;
import com.raphael.beachcleanup.exceptions.TooManyAttemptsException;

@Service
public class AbuseLimiter {
    private final AccountPolicy policy;
    private final AuthRateLimitRepository buckets;
    private final Clock clock;
    private final SecretKey key;
    private final TransactionTemplate tx;
    public AbuseLimiter(AccountPolicy policy, AuthRateLimitRepository buckets, Clock clock, SecretKey key, PlatformTransactionManager manager) {
        this.policy = policy; this.buckets = buckets; this.clock = clock; this.key = key;
        tx = new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void require(String scope, String identity, int maximum, long seconds) {
        long retry = consume(scope, identity, maximum, seconds);
        if (retry > 0) throw new TooManyAttemptsException(retry);
    }
    public long consume(String scope, String identity, int maximum, long seconds) {
        if (!policy.abuseEnabled) return 0;
        String hash;
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(key);
            hash = HexFormat.of().formatHex(mac.doFinal((scope + "\0" + identity).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return tx.execute(status -> {
                    var now = clock.instant();
                    var bucket = buckets.locked(hash).orElseGet(() -> buckets.saveAndFlush(new AuthRateLimit(hash, now)));
                    return bucket.consume(now, maximum, seconds);
                });
            } catch (DataIntegrityViolationException | TransientDataAccessException race) {
                // Concurrent creation/deadlock rolls back this short transaction before retrying.
            }
        }
        return seconds; // Fail closed if contention prevents a reliable count.
    }
}
