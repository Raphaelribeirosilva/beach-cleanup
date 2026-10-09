package com.raphael.beachcleanup.services;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.exceptions.ResourceNotFoundException;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;

@Service
@Transactional
public class CleanupEventService {

    @Autowired
    private CleanupEventRepository repository;

    @Transactional(readOnly = true)
    public List<CleanupEvent> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public CleanupEvent findById(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    public CleanupEvent insert(CleanupEvent obj) {
        obj.setId(null);
        return repository.save(obj);
    }

    public void delete(Long id) {
        CleanupEvent entity = findById(id);
        repository.delete(entity);
        repository.flush();
    }

    public CleanupEvent update(Long id, CleanupEvent obj) {
        CleanupEvent entity = findById(id);
        updateData(entity, obj);
        return repository.save(entity);
    }

    private void updateData(CleanupEvent entity, CleanupEvent obj) {
        entity.setTitle(obj.getTitle());
        entity.setLocation(obj.getLocation());
        entity.setDateTime(obj.getDateTime());
        entity.setStatus(obj.getStatus());
    }
}
