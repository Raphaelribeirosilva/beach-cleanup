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
    @Autowired private AuditService audit;

    @Autowired
    private TrashCollectionRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventStateService states;

    @Autowired
    private AccessPolicy access;

    @Autowired
    private CatalogService catalogs;

    @Autowired
    private CollectionDataRules dataRules;

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
        resolveCatalogs(obj);
        obj.setId(null);
        resolveReferences(obj, null);
        dataRules.validate(obj);
        var saved = repository.saveAndFlush(obj);
        audit.record("COLLECTION", saved.getId(), "CREATE", null, owner(saved), null, event(saved), null, null, null);
        return saved;
    }

    public void delete(Long id) { delete(id, null); }

    public void delete(Long id, Long version) {
        TrashCollection entity = findById(id);
        access.requireCollectionAccess(entity);
        FormVersion.requireCurrent(version, entity.getRowVersion());
        audit.record("COLLECTION", id, "DELETE", owner(entity), null, event(entity), null, null, null, null);
        repository.delete(entity);
        repository.flush();
    }

    public TrashCollection update(Long id, TrashCollection obj, Long version) {
        TrashCollection entity = findById(id);
        access.requireCollectionAccess(entity);
        FormVersion.requireCurrent(version, entity.getRowVersion());
        access.bindCollectionOwner(obj);
        resolveCatalogs(obj);
        resolveReferences(obj, entity.getEvent() == null ? null : entity.getEvent().getId());
        dataRules.validate(obj);
        Long oldUser = owner(entity), oldEvent = event(entity);
        updateData(entity, obj);
        if (!java.util.Objects.equals(oldUser, owner(entity)) || !java.util.Objects.equals(oldEvent, event(entity)))
            audit.record("COLLECTION", id, "TRANSFER", oldUser, owner(entity), oldEvent, event(entity), null, null, null);
        // Flush before returning so a stale version becomes HTTP 409, also when joining a transaction.
        return repository.saveAndFlush(entity);
    }

    private Long owner(TrashCollection c) { return c.getVolunteer() == null ? null : c.getVolunteer().getId(); }
    private Long event(TrashCollection c) { return c.getEvent() == null ? null : c.getEvent().getId(); }

    private void resolveCatalogs(TrashCollection obj) {
        var beach = catalogs.resolve("beaches", obj.getBeachReference(), obj.getBeach(), "beach");
        obj.setBeachReference(beach);
        obj.setBeach(beach.getName());
        var material = catalogs.resolve("materials", obj.getMaterialReference(), obj.getMaterialType(), "materialType");
        obj.setMaterialReference(material);
        obj.setMaterialType(material.getName());
    }

    private void updateData(TrashCollection entity, TrashCollection obj) {
        entity.setWeightKg(obj.getWeightKg());
        entity.setBeach(obj.getBeach());
        entity.setBeachReference(obj.getBeachReference());
        entity.setMaterialReference(obj.getMaterialReference());
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
