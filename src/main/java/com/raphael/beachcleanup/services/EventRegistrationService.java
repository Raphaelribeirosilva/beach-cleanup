package com.raphael.beachcleanup.services;

import java.util.Comparator;
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
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException(userId));
        return user.getEvents().stream().sorted(Comparator.comparing(CleanupEvent::getId))
            .map(CleanupEventDTO::new).toList();
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
        }
        users.flush();
    }

    public void cancel(Long userId, Long eventId) {
        validateEventId(eventId);
        User user = lockedUser(userId);
        CleanupEvent event = event(eventId);
        if (user.getEvents().remove(event)) {
            event.getUsers().remove(user);
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
