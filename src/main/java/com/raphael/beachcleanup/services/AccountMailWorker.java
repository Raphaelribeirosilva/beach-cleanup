package com.raphael.beachcleanup.services;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AccountMailWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AccountMailWorker.class);
    private final AccountActionService actions;
    private final AccountMailGateway gateway;
    public AccountMailWorker(AccountActionService actions, AccountMailGateway gateway) { this.actions = actions; this.gateway = gateway; }
    @Async("accountMailExecutor") @TransactionalEventListener
    public void send(AccountMailRequest request) {
        if (!gateway.enabled()) return;
        try {
            var mail = request.purpose().equals("PASSWORD_CHANGED")
                ? new AccountMailGateway.Mail(request.email(), "Senha alterada", "Sua senha foi redefinida e todas as sessões foram encerradas. Se não foi você, solicite recuperação e entre em contato com o suporte.")
                : actions.prepareMail(request);
            if (mail != null) gateway.send(mail);
        } catch (Exception ignored) { LOG.warn("Falha no envio de mensagem de conta; verifique o serviço de e-mail. Credenciais e destinatário omitidos."); }
    }
}
