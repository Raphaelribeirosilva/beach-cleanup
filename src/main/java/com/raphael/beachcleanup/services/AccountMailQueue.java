package com.raphael.beachcleanup.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.event.*;
import com.raphael.beachcleanup.repositories.UserRepository;

@Service
public class AccountMailQueue {
    public record Claim(long id, String email, String purpose, Long ownerId, Long credentialVersion,
            int attempts, Instant createdAt, String token) {
        @Override public String toString() { return "AccountMailClaim[id=" + id + ", REDACTED]"; }
    }
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final Clock clock;
    private final int maxAttempts;
    private final long leaseSeconds, initialDelay, maxDelay;

    public AccountMailQueue(JdbcTemplate jdbc, UserRepository users, Clock clock, Environment env) {
        this.jdbc = jdbc; this.users = users; this.clock = clock;
        maxAttempts = env.getProperty("account.mail.queue.max-attempts", Integer.class, 6);
        leaseSeconds = env.getProperty("account.mail.queue.lease-seconds", Long.class, 120L);
        initialDelay = env.getProperty("account.mail.queue.initial-retry-seconds", Long.class, 30L);
        maxDelay = env.getProperty("account.mail.queue.max-retry-seconds", Long.class, 600L);
        if (maxAttempts < 1 || maxAttempts > 20 || leaseSeconds < 30 || initialDelay < 1 || maxDelay < initialDelay)
            throw new IllegalArgumentException("Configuração inválida da fila de e-mail.");
    }

    /** Runs in the originating transaction; a rollback also removes the mail request. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void enqueue(AccountMailRequest request) {
        var owner = users.findByEmail(request.email()).orElse(null);
        var now = utc(clock.instant());
        jdbc.update("""
            insert into tb_account_mail_queue
            (recipient,purpose,owner_id,credential_version,status,attempts,next_attempt_at,created_at,updated_at)
            values (?,?,?,?,'READY',0,?,?,?)
            """, request.email(), request.purpose(), owner == null ? null : owner.getId(),
                owner == null ? null : owner.getCredentialVersion(), now, now, now);
    }

    /** A short CAS claim; no database locks/transactions are held during SMTP I/O. */
    // MySQL REPEATABLE_READ gap locks from the exhausted-lease sweep can deadlock
    // concurrent claims. READ_COMMITTED plus the conditional UPDATE retains exclusivity.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Claim claim() {
        var now = utc(clock.instant());
        // A process can die during its last attempt. Expired leases must still terminate.
        jdbc.update("""
            update tb_account_mail_queue set status='DEAD',claim_token=null,locked_until=null,
            updated_at=?,last_error='ATTEMPTS_EXHAUSTED'
            where status='PROCESSING' and locked_until<=? and attempts>=?
            """, now, now, maxAttempts);
        for (int race = 0; race < 5; race++) {
            var ids = jdbc.queryForList("""
                select id from tb_account_mail_queue where attempts<? and
                ((status='READY' and next_attempt_at<=?) or (status='PROCESSING' and locked_until<=?))
                order by id limit 1
                """, Long.class, maxAttempts, now, now);
            if (ids.isEmpty()) return null;
            long id = ids.getFirst(); String token = UUID.randomUUID().toString();
            int changed = jdbc.update("""
                update tb_account_mail_queue set status='PROCESSING',attempts=attempts+1,
                claim_token=?,locked_until=?,updated_at=? where id=? and attempts<? and
                ((status='READY' and next_attempt_at<=?) or (status='PROCESSING' and locked_until<=?))
                """, token, utc(clock.instant().plusSeconds(leaseSeconds)), now, id, maxAttempts, now, now);
            if (changed == 1) return jdbc.queryForObject("select * from tb_account_mail_queue where id=?",
                (rs, row) -> new Claim(rs.getLong("id"), rs.getString("recipient"), rs.getString("purpose"),
                    rs.getObject("owner_id", Long.class), rs.getObject("credential_version", Long.class),
                    rs.getInt("attempts"), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), token), id);
        }
        return null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(Claim claim, boolean sent) {
        return jdbc.update("""
            update tb_account_mail_queue set status=?,claim_token=null,locked_until=null,last_error=null,updated_at=?
            where id=? and status='PROCESSING' and claim_token=?
            """, sent ? "SENT" : "CANCELLED", utc(clock.instant()), claim.id(), claim.token()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(Claim claim) {
        long delay = initialDelay;
        for (int i = 1; i < claim.attempts() && delay < maxDelay; i++) delay = delay > maxDelay / 2 ? maxDelay : delay * 2;
        delay = Math.min(delay, maxDelay);
        jdbc.update("""
            update tb_account_mail_queue set status=?,next_attempt_at=?,claim_token=null,locked_until=null,
            last_error='DELIVERY_FAILED',updated_at=? where id=? and status='PROCESSING' and claim_token=?
            """, claim.attempts() >= maxAttempts ? "DEAD" : "READY", utc(clock.instant().plusSeconds(delay)),
                utc(clock.instant()), claim.id(), claim.token());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prune() {
        // Even when sending is disabled, stale requests eventually lose their recipient data.
        jdbc.update("""
            update tb_account_mail_queue set status='CANCELLED',updated_at=?,last_error=null
            where status='READY' and ((purpose='RESET_PASSWORD' and created_at<=?) or created_at<=?)
            """, utc(clock.instant()), utc(clock.instant().minusSeconds(900)), utc(clock.instant().minusSeconds(86400)));
        jdbc.update("delete from tb_account_mail_queue where status in ('SENT','CANCELLED','DEAD') and updated_at<?",
            utc(clock.instant().minusSeconds(7 * 86400L)));
    }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
