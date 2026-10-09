package com.raphael.beachcleanup.services;

import java.util.List;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.raphael.beachcleanup.exceptions.FieldValidationException;
import org.springframework.transaction.annotation.Transactional;

import com.raphael.beachcleanup.entities.Volunteer;
import com.raphael.beachcleanup.exceptions.ResourceNotFoundException;
import com.raphael.beachcleanup.repositories.VolunteerRepository;

@Service
@Transactional
public class VolunteerService {

    @Autowired
    private VolunteerRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<Volunteer> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Volunteer findById(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    public Volunteer insert(Volunteer obj) {
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
        obj.setTotalTrashCollecting(0.0);
        obj.getEvents().clear();
        return repository.save(obj);
    }

    public void delete(Long id) {
        Volunteer entity = findById(id);
        repository.delete(entity);
        repository.flush();
    }

    public Volunteer update(Long id, Volunteer obj) {
        Volunteer entity = findById(id);
        updateData(entity, obj);
        return repository.save(entity);
    }

    private void updateData(Volunteer entity, Volunteer obj) {
        entity.setName(obj.getName());
        entity.setEmail(obj.getEmail());
    }
}
