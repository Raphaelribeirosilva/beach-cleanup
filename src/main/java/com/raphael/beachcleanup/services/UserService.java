package com.raphael.beachcleanup.services;

import java.util.List;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.raphael.beachcleanup.exceptions.FieldValidationException;
import org.springframework.transaction.annotation.Transactional;

import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.entities.UserRole;
import com.raphael.beachcleanup.exceptions.EmailConflictException;
import com.raphael.beachcleanup.exceptions.ResourceNotFoundException;
import com.raphael.beachcleanup.repositories.UserRepository;

@Service
@Transactional
public class UserService {
    @Autowired private AuditService audit;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    @Autowired private com.raphael.beachcleanup.security.PasswordPolicy passwordPolicy;
    @Autowired private org.springframework.context.ApplicationEventPublisher events;
    @Autowired private com.raphael.beachcleanup.repositories.AuthSessionRepository sessions;
    @Autowired private java.time.Clock clock;
    @Autowired private com.raphael.beachcleanup.security.AccessPolicy access;
    @Autowired private com.raphael.beachcleanup.security.AbuseLimiter limiter;

    public User register(User user) {
        var saved = insert(user);
        events.publishEvent(new AccountMailRequest(saved.getEmail(), AccountActionService.VERIFY));
        return saved;
    }

    @Autowired
    private UserRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public User findById(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    public User insert(User obj) {
        String email = normalizeEmail(obj.getEmail());
        if (repository.existsByEmail(email)) {
            throw new EmailConflictException();
        }
        obj.setEmail(email);
        obj.setRole(UserRole.VOLUNTEER);
        obj.setCredentialVersion(0);
        String password = obj.getPassword();
        passwordPolicy.validate(password, "password");
        if (password == null || password.isBlank()) {
            throw new FieldValidationException("password", "A senha é obrigatória");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new FieldValidationException("password",
                "A senha deve ter no máximo 72 bytes em UTF-8");
        }
        obj.setPassword(passwordEncoder.encode(password));
        obj.setEmailVerified(false);
        obj.setId(null);
        obj.setTotalCollectedKg(0.0);
        obj.getEvents().clear();
        return repository.save(obj);
    }

    public void delete(Long id) { delete(id, null); }

    public void delete(Long id, Long version) {
        User entity = repository.findByIdForAuthentication(id).orElseThrow(() -> new ResourceNotFoundException(id));
        entityManager.refresh(entity);
        FormVersion.requireCurrent(version, entity.getRowVersion());
        audit.requireNoHistory("USER", id);
        if (!entity.getEvents().isEmpty() || !entity.getCollection().isEmpty())
            throw new com.raphael.beachcleanup.exceptions.EventStateConflictException("Usuários com inscrições ou coletas devem ser preservados.");
        audit.record("USER", id, "DELETE", null, null, null, null, null, null, null);
        repository.delete(entity);
        repository.flush();
    }

    public User update(Long id, User obj, Long version) {
        return update(id, obj, version, null);
    }

    public User update(Long id, User obj, Long version, String currentPassword) {
        if (!access.isAdmin() && currentPassword != null && !currentPassword.isEmpty())
            limiter.require("email-change", id.toString(), 5, 900);
        User entity = repository.findByIdForAuthentication(id).orElseThrow(() -> new ResourceNotFoundException(id));
        FormVersion.requireCurrent(version, entity.getRowVersion());
        String email = normalizeEmail(obj.getEmail());
        if (repository.existsByEmailAndIdNot(email, id)) {
            throw new EmailConflictException();
        }
        obj.setEmail(email);
        boolean emailChanged = !entity.getEmail().equals(email);
        if (emailChanged) {
            if (!access.isAdmin()) {
                if (currentPassword == null || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                        || !passwordEncoder.matches(currentPassword, entity.getPassword()))
                    throw new FieldValidationException("currentPassword", "Confira sua senha atual para alterar o e-mail.");
            }
            entity.setEmailVerified(false);
            entity.setCredentialVersion(Math.incrementExact(entity.getCredentialVersion()));
        }
        updateData(entity, obj);
        var saved = repository.saveAndFlush(entity);
        if (emailChanged) {
            sessions.revokeByUser(entity.getId(), clock.instant());
            events.publishEvent(new AccountMailRequest(email, AccountActionService.VERIFY));
        }
        return saved;
    }

    private void updateData(User entity, User obj) {
        entity.setName(obj.getName());
        entity.setEmail(obj.getEmail());
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
