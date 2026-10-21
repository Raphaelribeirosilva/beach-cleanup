package com.raphael.beachcleanup.services;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AccountMailWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AccountMailWorker.class);
    private final AccountActionService actions;
    private final AccountMailGateway gateway;
    private final AccountMailQueue queue;
    private final ThreadPoolTaskExecutor executor;
    public AccountMailWorker(AccountActionService actions, AccountMailGateway gateway, AccountMailQueue queue,
            @Qualifier("accountMailExecutor") ThreadPoolTaskExecutor executor) {
        this.actions = actions; this.gateway = gateway; this.queue = queue; this.executor = executor;
    }
    @TransactionalEventListener
    public void wakeAfterCommit(AccountMailRequest request) { wake(); }

    @Scheduled(fixedDelayString = "${account.mail.queue.poll-delay-ms:1000}",
        initialDelayString = "${account.mail.queue.poll-initial-delay-ms:1000}")
    public void wake() {
        if (!gateway.enabled()) return;
        try { executor.execute(this::drain); }
        catch (TaskRejectedException ignored) { /* The committed request stays in the database; the next poll retries. */ }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void maintain() {
        try { queue.prune(); }
        catch (Exception ignored) { LOG.warn("Manutenção da fila de e-mail adiada; nova tentativa automática."); }
    }

    public void drain() {
        if (!gateway.enabled()) return;
        try {
            for (int i = 0; i < 20; i++) {
                var claim = queue.claim();
                if (claim == null) break;
                try {
                    var mail = actions.prepareQueuedMail(claim);
                    if (mail != null) gateway.send(mail);
                    queue.complete(claim, mail != null);
                } catch (Exception ignored) {
                    queue.retry(claim);
                    // Transport exception text can contain addresses and credentials. Do not log it.
                    LOG.warn("Falha no envio de e-mail; tarefa={}, tentativa={}. Consulte o provedor e a fila.",
                        claim.id(), claim.attempts());
                }
            }
        } catch (Exception ignored) {
            LOG.warn("Fila de e-mail indisponível; uma nova consulta será feita automaticamente.");
        }
    }
}
