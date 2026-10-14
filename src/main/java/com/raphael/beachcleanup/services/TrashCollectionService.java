package com.raphael.beachcleanup.services;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.exceptions.ResourceNotFoundException;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.exceptions.FieldValidationException;
import com.raphael.beachcleanup.security.AccessPolicy;

@Service
@Transactional
public class TrashCollectionService {

    @Autowired
    private TrashCollectionRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventStateService states;

    @Autowired
    private AccessPolicy access;

    @Transactional(readOnly = true)
    public List<TrashCollection> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public TrashCollection findById(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    public TrashCollection insert(TrashCollection obj) {
        obj.setId(null);
        resolveReferences(obj, null);
        return repository.save(obj);
    }

    public void delete(Long id) {
        TrashCollection entity = findById(id);
        access.requireCollectionAccess(entity);
        repository.delete(entity);
        repository.flush();
    }

    public TrashCollection update(Long id, TrashCollection obj) {
        TrashCollection entity = findById(id);
        access.requireCollectionAccess(entity);
        access.bindCollectionOwner(obj);
        resolveReferences(obj, entity.getEvent() == null ? null : entity.getEvent().getId());
        updateData(entity, obj);
        // Flush before returning so a stale version becomes HTTP 409, also when joining a transaction.
        return repository.saveAndFlush(entity);
    }

    private void updateData(TrashCollection entity, TrashCollection obj) {
        entity.setWeightKg(obj.getWeightKg());
        entity.setBeach(obj.getBeach());
        entity.setMaterialType(obj.getMaterialType());
        entity.setRegistrationDate(obj.getRegistrationDate());
        entity.setVolunteer(obj.getVolunteer());
        entity.setEvent(obj.getEvent());
    }

    private void resolveReferences(TrashCollection obj, Long previousEventId) {
        if (obj.getVolunteer() != null) {
            Long userId = obj.getVolunteer().getId();
            if (userId == null || userId <= 0) {
                throw new FieldValidationException("volunteer.id", "Informe um ID positivo para o vínculo");
            }
            obj.setVolunteer(userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(userId)));
        }
        if (obj.getEvent() != null) {
            Long eventId = obj.getEvent().getId();
            if (eventId == null || eventId <= 0) {
                throw new FieldValidationException("event.id", "Informe um ID positivo para o vínculo");
            }
            var event = states.lockedEvent(eventId);
            if (!eventId.equals(previousEventId)) {
                states.requireNewRecordsAllowed(event);
            }
            obj.setEvent(event);
        }
    }
}
