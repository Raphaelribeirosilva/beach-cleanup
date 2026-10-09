package com.raphael.beachcleanup.security;

import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.entities.Volunteer;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.VolunteerRepository;

@Component("accessPolicy")
@Transactional(readOnly = true)
public class AccessPolicy {
    private final TrashCollectionRepository collections;
    private final VolunteerRepository volunteers;

    public AccessPolicy(TrashCollectionRepository collections, VolunteerRepository volunteers) {
        this.collections = collections;
        this.volunteers = volunteers;
    }

    public boolean isAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    public long currentId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    public boolean canAccessVolunteer(Long id) {
        return isAdmin() || id != null && id == currentId();
    }

    public boolean canAccessCollection(Long id) {
        return isAdmin() || collections.findById(id)
            .map(collection -> collection.getVolunteer() != null
                && collection.getVolunteer().getId() == currentId()).orElse(false);
    }

    public List<TrashCollection> visibleCollections() {
        return isAdmin() ? collections.findAll() : collections.findAllByVolunteerId(currentId());
    }

    public void bindCollectionOwner(TrashCollection collection) {
        if (isAdmin()) {
            return;
        }
        if (collection.getVolunteer() != null && !canAccessVolunteer(collection.getVolunteer().getId())) {
            throw new AccessDeniedException("Não é permitido registrar coleta em nome de outro usuário.");
        }
        Volunteer owner = volunteers.findById(currentId()).orElseThrow(
            () -> new AccessDeniedException("Usuário indisponível."));
        collection.setVolunteer(owner);
    }
}
