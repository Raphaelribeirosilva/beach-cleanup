package com.raphael.beachcleanup.services;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import com.raphael.beachcleanup.config.AccountPolicy;

@Service
public class AccountMailGateway {
    public record Mail(String recipient, String subject, String text) {
        @Override public String toString() { return "Mail[REDACTED]"; }
    }
    private final AccountPolicy policy;
    private final ObjectProvider<JavaMailSender> sender;
    private final BrevoMailTransport brevo;
    private final ConcurrentLinkedQueue<Mail> simulated = new ConcurrentLinkedQueue<>();
    public AccountMailGateway(AccountPolicy policy, ObjectProvider<JavaMailSender> sender) {
        this.policy = policy; this.sender = sender;
        this.brevo = policy.mailMode.equals("brevo") ? new BrevoMailTransport(policy) : null;
    }
    public boolean enabled() { return !policy.mailMode.equals("disabled"); }
    public List<Mail> simulatedMessages() {
        if (!policy.test || !policy.mailMode.equals("memory")) throw new IllegalStateException("Captura disponível somente no teste simulado.");
        return List.copyOf(simulated);
    }
    public void clearSimulated() { if (policy.test) simulated.clear(); }
    public void send(Mail mail) {
        if (brevo != null) { brevo.send(mail); return; }
        if (policy.mailMode.equals("memory")) {
            simulated.add(mail); while (simulated.size() > 100) simulated.poll(); return;
        }
        if (!policy.mailMode.equals("smtp")) throw new IllegalStateException("Envio de e-mail não configurado.");
        var message = new SimpleMailMessage(); message.setFrom(policy.from); message.setTo(mail.recipient());
        message.setSubject(mail.subject()); message.setText(mail.text());
        sender.getObject().send(message);
    }
}
