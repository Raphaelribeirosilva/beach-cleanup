package com.raphael.beachcleanup.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.EventStatus;
import com.raphael.beachcleanup.exceptions.EventStateConflictException;
import com.raphael.beachcleanup.exceptions.ResourceNotFoundException;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class EventStateService {
    private final CleanupEventRepository events;
    @PersistenceContext
    private EntityManager entityManager;

    public EventStateService(CleanupEventRepository events) {
        this.events = events;
    }

    public CleanupEvent lockedEvent(Long id) {
        CleanupEvent event = events.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException(id));
        entityManager.refresh(event);
        return event;
    }

    public void requireNewRecordsAllowed(CleanupEvent event) {
        if (!EventStatus.parse(event.getStatus()).acceptsNewRecords()) {
            throw new EventStateConflictException("O evento não aceita novas inscrições ou coletas neste status.");
        }
    }
}
