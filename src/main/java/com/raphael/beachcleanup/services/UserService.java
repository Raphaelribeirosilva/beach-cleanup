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
        if (password == null || password.isBlank()) {
            throw new FieldValidationException("password", "A senha é obrigatória");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new FieldValidationException("password",
                "A senha deve ter no máximo 72 bytes em UTF-8");
        }
        obj.setPassword(passwordEncoder.encode(password));
        obj.setId(null);
        obj.setTotalCollectedKg(0.0);
        obj.getEvents().clear();
        return repository.save(obj);
    }

    public void delete(Long id) {
        User entity = findById(id);
        repository.delete(entity);
        repository.flush();
    }

    public User update(Long id, User obj) {
        User entity = findById(id);
        String email = normalizeEmail(obj.getEmail());
        if (repository.existsByEmailAndIdNot(email, id)) {
            throw new EmailConflictException();
        }
        obj.setEmail(email);
        updateData(entity, obj);
        return repository.save(entity);
    }

    private void updateData(User entity, User obj) {
        entity.setName(obj.getName());
        entity.setEmail(obj.getEmail());
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
