package com.raphael.beachcleanup.security;

import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.UserRepository;

@Component("accessPolicy")
@Transactional(readOnly = true)
public class AccessPolicy {
    private final TrashCollectionRepository collections;
    private final UserRepository users;

    public AccessPolicy(TrashCollectionRepository collections, UserRepository users) {
        this.collections = collections;
        this.users = users;
    }

    public boolean isAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    public long currentId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    public boolean canAccessUser(Long id) {
        return isAdmin() || id != null && id == currentId();
    }

    public boolean canAccessCollection(Long id) {
        return isAdmin() || collections.findById(id)
            .map(collection -> collection.getVolunteer() != null
                && collection.getVolunteer().getId() == currentId()).orElse(false);
    }

    public List<TrashCollection> visibleCollections() {
        Long owner = isAdmin() ? null : currentId();
        org.springframework.data.jpa.domain.Specification<TrashCollection> filter = (r,q,b) -> owner == null
            ? b.conjunction() : b.equal(r.get("volunteer").get("id"), owner);
        var page = collections.findAll(filter, org.springframework.data.domain.PageRequest.of(0,100,org.springframework.data.domain.Sort.by("id")));
        if (page.getTotalElements() > 100) throw new com.raphael.beachcleanup.exceptions.FieldValidationException("size",
            "Esta listagem excede 100 registros. Use /trashCollection/search com paginação.");
        return page.getContent();
    }

    /** Check the same managed snapshot that will be written by the service transaction. */
    public void requireCollectionAccess(TrashCollection collection) {
        if (isAdmin()) {
            return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken
                || collection.getVolunteer() == null
                || !canAccessUser(collection.getVolunteer().getId())) {
            throw new AccessDeniedException("Não é permitido alterar ou excluir a coleta de outro usuário.");
        }
    }

    public void bindCollectionOwner(TrashCollection collection) {
        if (isAdmin()) {
            return;
        }
        if (collection.getVolunteer() != null && !canAccessUser(collection.getVolunteer().getId())) {
            throw new AccessDeniedException("Não é permitido registrar coleta em nome de outro usuário.");
        }
        User owner = users.findById(currentId()).orElseThrow(
            () -> new AccessDeniedException("Usuário indisponível."));
        collection.setVolunteer(owner);
    }
}
