package com.raphael.beachcleanup.services;

import java.time.Clock;
import java.time.Instant;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.raphael.beachcleanup.config.AuthRetentionPolicy;

/** Delete only expired security state, in short bounded transactions. */
@Service
public class AuthDataMaintenance {
    public record Result(int sessions, int actionTokens, int rateLimits) {}
    private enum Kind {
        SESSION("AuthSession", "id", "expiresAt"),
        ACTION("AccountActionToken", "tokenHash", "expiresAt"),
        RATE("AuthRateLimit", "bucketKey", "windowStart");
        final String entity, key, time;
        Kind(String entity, String key, String time) { this.entity = entity; this.key = key; this.time = time; }
    }
    private final EntityManager entities;
    private final Clock clock;
    private final AuthRetentionPolicy policy;
    private final TransactionTemplate batch;

    public AuthDataMaintenance(EntityManager entities, Clock clock, AuthRetentionPolicy policy, PlatformTransactionManager manager) {
        this.entities = entities; this.clock = clock; this.policy = policy;
        batch = new TransactionTemplate(manager);
        batch.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        batch.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Result cleanup() {
        var now = clock.instant();
        return new Result(clean(Kind.SESSION, now.minus(policy.sessions)),
            clean(Kind.ACTION, now.minus(policy.actionTokens)), clean(Kind.RATE, now.minus(policy.rateLimits)));
    }

    private int clean(Kind kind, Instant cutoff) {
        int total = 0;
        for (int i = 0; i < policy.maxBatches; i++) {
            // Identifiers below are a private enum, never caller input. Instant bindings preserve UTC on H2/MySQL.
            int[] progress = batch.execute(tx -> {
                var ids = entities.createQuery("select e." + kind.key + " from " + kind.entity + " e where e."
                    + kind.time + " <= :cutoff order by e." + kind.time + ", e." + kind.key, String.class)
                    .setParameter("cutoff", cutoff).setMaxResults(policy.batchSize).getResultList();
                if (ids.isEmpty()) return new int[]{0, 0};
                // Recheck the timestamp: a concurrent request may have renewed a selected rate-limit bucket.
                int removed = entities.createQuery("delete from " + kind.entity + " e where e." + kind.key
                    + " in :ids and e." + kind.time + " <= :cutoff")
                    .setParameter("ids", ids).setParameter("cutoff", cutoff).executeUpdate();
                return new int[]{ids.size(), removed};
            });
            total += progress[1];
            if (progress[0] < policy.batchSize || progress[1] == 0) break;
        }
        // Refresh tokens are deliberately removed only by the session FK's ON DELETE CASCADE.
        return total;
    }
}
