package com.raphael.beachcleanup.services;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.exceptions.ResourceNotFoundException;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.repositories.VolunteerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class TrashCollectionService {

    @Autowired
    private TrashCollectionRepository repository;

    @Autowired
    private VolunteerRepository volunteerRepository;

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
        entity.setWeithKg(obj.getWeithKg());
        entity.setBeach(obj.getBeach());
        entity.setMaterialType(obj.getMaterialType());
        entity.setRegitrationDate(obj.getRegitrationDate());
        entity.setVolunteer(obj.getVolunteer());
        entity.setEvent(obj.getEvent());
    }

    private void resolveReferences(TrashCollection obj) {
        if (obj.getVolunteer() != null) {
            Long volunteerId = obj.getVolunteer().getId();
            if (volunteerId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe volunteer.id");
            }
            obj.setVolunteer(volunteerRepository.findById(volunteerId)
                .orElseThrow(() -> new ResourceNotFoundException(volunteerId)));
        }
        if (obj.getEvent() != null) {
            Long eventId = obj.getEvent().getId();
            if (eventId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe event.id");
            }
            obj.setEvent(eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException(eventId)));
        }
    }
}
