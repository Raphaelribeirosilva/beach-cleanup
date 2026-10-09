package com.raphael.beachcleanup.services;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.exceptions.ResourceNotFoundException;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.exceptions.FieldValidationException;

@Service
@Transactional
public class TrashCollectionService {

    @Autowired
    private TrashCollectionRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CleanupEventRepository eventRepository;

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
        resolveReferences(obj);
        return repository.save(obj);
    }

    public void delete(Long id) {
        TrashCollection entity = findById(id);
        repository.delete(entity);
        repository.flush();
    }

    public TrashCollection update(Long id, TrashCollection obj) {
        TrashCollection entity = findById(id);
        resolveReferences(obj);
        updateData(entity, obj);
        return repository.save(entity);
    }

    private void updateData(TrashCollection entity, TrashCollection obj) {
        entity.setWeightKg(obj.getWeightKg());
        entity.setBeach(obj.getBeach());
        entity.setMaterialType(obj.getMaterialType());
        entity.setRegistrationDate(obj.getRegistrationDate());
        entity.setVolunteer(obj.getVolunteer());
        entity.setEvent(obj.getEvent());
    }

    private void resolveReferences(TrashCollection obj) {
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
            obj.setEvent(eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException(eventId)));
        }
    }
}
