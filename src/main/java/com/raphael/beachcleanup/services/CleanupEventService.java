package com.raphael.beachcleanup.services;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.EventStatus;
import com.raphael.beachcleanup.exceptions.EventStateConflictException;
import com.raphael.beachcleanup.exceptions.ResourceNotFoundException;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;

@Service
@Transactional
public class CleanupEventService {
    @Autowired private AuditService audit;

    @Autowired
    private CleanupEventRepository repository;
    @Autowired
    private EventStateService states;

    @Autowired
    private CatalogService catalogs;

    @Autowired
    private com.raphael.beachcleanup.repositories.TrashCollectionRepository collections;

    @Transactional(readOnly = true)
    public List<CleanupEvent> findAll() {
        var page = repository.findAll((org.springframework.data.jpa.domain.Specification<CleanupEvent>) null,
            org.springframework.data.domain.PageRequest.of(0, 100, org.springframework.data.domain.Sort.by("id")));
        if (page.getTotalElements() > 100) throw new com.raphael.beachcleanup.exceptions.FieldValidationException("size",
            "Esta listagem excede 100 registros. Use a rota /search com paginação.");
        return page.getContent();
    }

    @Transactional(readOnly = true)
    public CleanupEvent findById(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    public CleanupEvent insert(CleanupEvent obj) {
        validateDate(obj);
        resolveCatalogs(obj);
        EventStatus status = EventStatus.parse(obj.getStatus());
        if (status != EventStatus.SCHEDULED) {
            throw new EventStateConflictException("Novos eventos devem começar como Agendado.");
        }
        obj.setStatus(status.value());
        obj.setId(null);
        return repository.save(obj);
    }

    public void delete(Long id) { delete(id, null); }

    public void delete(Long id, Long version) {
        CleanupEvent entity = states.lockedEvent(id);
        FormVersion.requireCurrent(version, entity.getRowVersion());
        audit.requireNoHistory("EVENT", id);
        if (EventStatus.parse(entity.getStatus()) != EventStatus.SCHEDULED)
            throw new EventStateConflictException("Preserve o histórico deste evento. Use o estado Cancelado quando aplicável.");
        if (!entity.getUsers().isEmpty() || !collections.eventLinksForUpdate(id).isEmpty())
            throw new EventStateConflictException("Eventos com inscrições ou coletas devem ser preservados.");
        audit.record("EVENT", id, "DELETE", null, null, null, null, null, null, null);
        repository.delete(entity);
        repository.flush();
    }

    public CleanupEvent update(Long id, CleanupEvent obj, Long version) {
        validateDate(obj);
        resolveCatalogs(obj);
        CleanupEvent entity = states.lockedEvent(id);
        FormVersion.requireCurrent(version, entity.getRowVersion());
        boolean changed = entity.getBeachReference() == null
            || !entity.getBeachReference().getId().equals(obj.getBeachReference().getId())
            || entity.getDateTime() == null || !entity.getDateTime().toLocalDate().equals(obj.getDateTime().toLocalDate());
        // Unknown legacy fields stay pending; they must not prevent identifying the event first.
        // Only contradicting known fields block the change. Never fill the collections implicitly.
        if (changed && collections.eventLinksForUpdate(id).stream().anyMatch(link ->
                (link.getBeachId() != null && !obj.getBeachReference().getId().equals(link.getBeachId()))
                || (link.getRegistrationDate() != null && !obj.getDateTime().toLocalDate().equals(link.getRegistrationDate())))) {
            throw new EventStateConflictException("A nova praia/data conflita com coletas vinculadas. Corrija ou desvincule essas coletas antes de alterar o evento.");
        }
        EventStatus next = EventStatus.parse(obj.getStatus());
        if (!EventStatus.parse(entity.getStatus()).canTransitionTo(next)) {
            throw new EventStateConflictException("Transição de status não permitida para o evento.");
        }
        obj.setStatus(next.value());
        if (!entity.getStatus().equals(next.value()))
            audit.record("EVENT", id, "STATE_CHANGE", null, null, null, null, null, entity.getStatus(), next.value());
        updateData(entity, obj);
        // lockedEvent returns a managed entity. Flush dirty checking directly; merging
        // it after refresh can replace newly persisted collections in this transaction.
        repository.flush();
        return entity;
    }

    private void resolveCatalogs(CleanupEvent obj) {
        var beach = catalogs.resolve("beaches", obj.getBeachReference(), obj.getLocation(), "location");
        obj.setBeachReference(beach);
        obj.setLocation(beach.getName());
    }

    private void validateDate(CleanupEvent obj) {
        if (obj.getDateTime() == null || obj.getDateTime().toLocalDate().isBefore(CollectionDataRules.FIRST_DATE)
                || obj.getDateTime().getYear() > 9999) {
            throw new com.raphael.beachcleanup.exceptions.FieldValidationException("dateTime", "Informe data/hora entre 01/01/1900 e 31/12/9999.");
        }
    }

    private void updateData(CleanupEvent entity, CleanupEvent obj) {
        entity.setTitle(obj.getTitle());
        entity.setLocation(obj.getLocation());
        entity.setBeachReference(obj.getBeachReference());
        entity.setDateTime(obj.getDateTime());
        entity.setStatus(obj.getStatus());
    }
}
