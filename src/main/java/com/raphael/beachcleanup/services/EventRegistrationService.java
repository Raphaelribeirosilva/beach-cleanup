package com.raphael.beachcleanup.services;

import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.raphael.beachcleanup.dto.CleanupEventDTO;
import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.exceptions.FieldValidationException;
import com.raphael.beachcleanup.exceptions.ResourceNotFoundException;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.repositories.UserRepository;

@Service
@Transactional
public class EventRegistrationService {
    @org.springframework.beans.factory.annotation.Autowired private AuditService audit;
    private final UserRepository users;
    private final CleanupEventRepository events;
    private final EventStateService states;
    @PersistenceContext
    private EntityManager entityManager;

    public EventRegistrationService(UserRepository users, CleanupEventRepository events, EventStateService states) {
        this.users = users;
        this.events = events;
        this.states = states;
    }

    @Transactional(readOnly = true)
    public List<CleanupEventDTO> findMyEvents(Long userId) {
        var page = findPage(userId, List.of(), org.springframework.data.domain.PageRequest.of(0,100,
            org.springframework.data.domain.Sort.by("id")));
        if (page.totalElements() > 100) throw new FieldValidationException("size",
            "Use /users/me/events/search para consultar as inscrições com paginação.");
        return page.content();
    }

    @Transactional(readOnly = true)
    public com.raphael.beachcleanup.dto.RegistrationPageDTO findPage(Long userId, List<Long> eventIds,
            org.springframework.data.domain.Pageable pageable) {
        if (!users.existsById(userId)) throw new ResourceNotFoundException(userId);
        if (eventIds.size() > 100 || eventIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new FieldValidationException("eventIds", "Informe até 100 IDs positivos.");
        var page = events.registrations(userId, pageable);
        var selected = eventIds.isEmpty() ? List.<Long>of() : events.registeredIds(userId,eventIds.stream().distinct().toList());
        return new com.raphael.beachcleanup.dto.RegistrationPageDTO(
            page.getContent().stream().map(CleanupEventDTO::new).toList(),page.getNumber(),page.getSize(),
            page.getTotalElements(),page.getTotalPages(),selected);
    }

    public void register(Long userId, Long eventId) {
        validateEventId(eventId);
        // Serialize changes to this user's owning collection, including simultaneous registrations.
        User user = lockedUser(userId);
        CleanupEvent event = states.lockedEvent(eventId);
        if (!user.getEvents().contains(event)) {
            states.requireNewRecordsAllowed(event);
            user.getEvents().add(event);
            event.getUsers().add(user);
            audit.record("EVENT", eventId, "REGISTER", null, userId, null, eventId, null, null, null);
        }
        users.flush();
    }

    public void cancel(Long userId, Long eventId) {
        validateEventId(eventId);
        User user = lockedUser(userId);
        CleanupEvent event = event(eventId);
        if (user.getEvents().remove(event)) {
            event.getUsers().remove(user);
            audit.record("EVENT", eventId, "UNREGISTER", userId, null, eventId, null, null, null, null);
        }
        users.flush();
    }

    private User lockedUser(Long id) {
        User user = users.findByIdForRegistration(id).orElseThrow(() -> new ResourceNotFoundException(id));
        // Authentication may have loaded the user before another request acquired the lock.
        entityManager.refresh(user);
        return user;
    }

    private CleanupEvent event(Long id) {
        return events.findById(id).orElseThrow(() -> new ResourceNotFoundException(id));
    }

    private void validateEventId(Long id) {
        if (id == null || id <= 0) {
            throw new FieldValidationException("eventId", "Informe um ID positivo para o evento");
        }
    }
}
