package com.raphael.beachcleanup.services;

import java.time.Clock;
import java.security.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.raphael.beachcleanup.config.AccountPolicy;
import com.raphael.beachcleanup.entities.*;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.security.*;
import com.raphael.beachcleanup.exceptions.FieldValidationException;

@Service
public class AccountActionService {
    public static final String RESET = "RESET_PASSWORD", VERIFY = "VERIFY_EMAIL";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final AccountActionTokenRepository tokens;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwords;
    private final Clock clock;
    private final AccountPolicy policy;
    private final ApplicationEventPublisher events;
    private final AbuseLimiter limiter;
    public AccountActionService(UserRepository users, AccountActionTokenRepository tokens, AuthSessionRepository sessions,
            PasswordEncoder encoder, PasswordPolicy passwords, Clock clock, AccountPolicy policy, ApplicationEventPublisher events, AbuseLimiter limiter) {
        this.users = users; this.tokens = tokens; this.sessions = sessions; this.encoder = encoder; this.passwords = passwords;
        this.clock = clock; this.policy = policy; this.events = events; this.limiter = limiter;
    }
    @Transactional
    public void request(String email, String purpose) {
        email = UserService.normalizeEmail(email);
        // Same accounting/queueing for known, unknown and unverified addresses.
        if (limiter.consume("account-mail-" + purpose, email, 3, 3600) == 0)
            events.publishEvent(new AccountMailRequest(email, purpose));
    }
    @Transactional
    public AccountMailGateway.Mail prepareMail(AccountMailRequest request) {
        var id = users.findIdByEmail(request.email());
        var user = id.flatMap(users::findByIdForAuthentication).orElse(null);
        return prepareMail(request, user);
    }
    @Transactional
    public AccountMailGateway.Mail prepareQueuedMail(AccountMailQueue.Claim claim) {
        long lifetime = claim.purpose().equals(RESET) ? 900 : 86400;
        if (claim.ownerId() == null || !claim.createdAt().plusSeconds(lifetime).isAfter(clock.instant())) return null;
        var user = users.findByIdForAuthentication(claim.ownerId()).orElse(null);
        if (user == null || !user.getEmail().equals(claim.email())
                || !Objects.equals(claim.credentialVersion(), user.getCredentialVersion())) return null;
        if (claim.purpose().equals("PASSWORD_CHANGED")) return new AccountMailGateway.Mail(user.getEmail(),
            "Senha alterada", "Sua senha foi redefinida e todas as sessões foram encerradas. Se não foi você, solicite recuperação e entre em contato com o suporte.");
        return prepareMail(new AccountMailRequest(claim.email(), claim.purpose()), user);
    }
    private AccountMailGateway.Mail prepareMail(AccountMailRequest request, User user) {
        if (user == null || !user.getEmail().equals(request.email())
                || !Set.of(VERIFY, RESET).contains(request.purpose())
                || request.purpose().equals(VERIFY) && user.isEmailVerified()
                || request.purpose().equals(RESET) && !user.isEmailVerified()) return null;
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        boolean reset = request.purpose().equals(RESET);
        tokens.save(new AccountActionToken(hash(token), user, request.purpose(), clock.instant().plusSeconds(reset ? 900 : 86400)));
        String link = policy.baseUrl.replaceAll("/+$", "") + "/#" + (reset ? "reset=" : "verify=") + token;
        return new AccountMailGateway.Mail(user.getEmail(), reset ? "Redefinir senha" : "Confirmar e-mail",
            (reset ? "Escolha uma nova senha. O link expira em 15 minutos." : "Confirme seu e-mail. O link expira em 24 horas.")
            + "\n\n" + link + "\n\nSe não solicitou esta mensagem, ignore-a. Não compartilhe este link.");
    }
    @Transactional
    public void reset(String token, String password) {
        passwords.validate(password, "newPassword");
        var locked = lock(token, RESET);
        var user = locked.getUser();
        if (!user.isEmailVerified() || encoder.matches(password, user.getPassword()))
            throw new FieldValidationException("newPassword", "Use uma senha diferente da atual e um link válido.");
        user.setPassword(encoder.encode(password));
        user.setCredentialVersion(Math.incrementExact(user.getCredentialVersion()));
        locked.consume(); users.saveAndFlush(user); sessions.revokeByUser(user.getId(), clock.instant());
        events.publishEvent(new AccountMailRequest(user.getEmail(), "PASSWORD_CHANGED"));
    }
    @Transactional
    public void verify(String token) {
        var locked = lock(token, VERIFY);
        locked.getUser().setEmailVerified(true); locked.consume();
    }
    private AccountActionToken lock(String token, String purpose) {
        String hash = hash(token);
        Long owner = tokens.owner(hash).orElseThrow(AccountActionService::invalid);
        var user = users.findByIdForAuthentication(owner).orElseThrow(AccountActionService::invalid);
        var action = tokens.findLocked(hash).orElseThrow(AccountActionService::invalid);
        if (!action.valid(user, purpose, clock.instant())) throw invalid();
        return action;
    }
    private static FieldValidationException invalid() { return new FieldValidationException("token", "Link inválido, expirado ou já utilizado. Solicite uma nova mensagem."); }
    private static String hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
