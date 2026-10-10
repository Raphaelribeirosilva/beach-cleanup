package com.raphael.beachcleanup.services;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.raphael.beachcleanup.dto.*;
import com.raphael.beachcleanup.entities.*;
import com.raphael.beachcleanup.exceptions.FieldValidationException;
import com.raphael.beachcleanup.repositories.*;
import com.raphael.beachcleanup.security.AccessPolicy;

@Service
@Transactional(readOnly = true)
public class SearchService {
    private final UserRepository users;
    private final CleanupEventRepository events;
    private final TrashCollectionRepository collections;
    private final CollectedTotalsService totals;
    private final AccessPolicy access;

    public SearchService(UserRepository users, CleanupEventRepository events,
            TrashCollectionRepository collections, CollectedTotalsService totals, AccessPolicy access) {
        this.users = users;
        this.events = events;
        this.collections = collections;
        this.totals = totals;
        this.access = access;
    }

    public PageDTO<UserDTO> users(String name, String email, Pageable pageable) {
        if (!access.isAdmin()) {
            throw new AccessDeniedException("Somente administradores podem buscar contas.");
        }
        validateText("name", name);
        validateText("email", email);
        Specification<User> filter = (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            contains(predicates, root, builder, "name", name);
            contains(predicates, root, builder, "email", email);
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        var page = users.findAll(filter, pageable);
        return PageDTO.of(page, totals.users(page.getContent()));
    }

    public PageDTO<CleanupEventDTO> events(String location, String status,
            LocalDateTime from, LocalDateTime to, Pageable pageable) {
        validateText("location", location);
        validateRange(from, to);
        String state = status == null ? null : EventStatus.parse(status).value();
        Specification<CleanupEvent> filter = (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            contains(predicates, root, builder, "location", location);
            if (state != null) predicates.add(builder.equal(root.get("status"), state));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("dateTime"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("dateTime"), to));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        var page = events.findAll(filter, pageable);
        return PageDTO.of(page, page.getContent().stream().map(CleanupEventDTO::new).toList());
    }

    public PageDTO<TrashCollectionDTO> collections(String beach, String materialType, LocalDate from,
            LocalDate to, Long volunteerId, Long eventId, Pageable pageable) {
        validateText("beach", beach);
        validateText("materialType", materialType);
        validateRange(from, to);
        validateId("volunteerId", volunteerId);
        validateId("eventId", eventId);
        Long owner = volunteerId;
        if (!access.isAdmin()) {
            long current = access.currentId();
            if (owner != null && owner != current) {
                throw new AccessDeniedException("Não é permitido buscar coletas de outro usuário.");
            }
            owner = current;
        }
        final Long visibleOwner = owner;
        Specification<TrashCollection> filter = (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            contains(predicates, root, builder, "beach", beach);
            contains(predicates, root, builder, "materialType", materialType);
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("registrationDate"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("registrationDate"), to));
            if (visibleOwner != null) predicates.add(builder.equal(root.get("volunteer").get("id"), visibleOwner));
            if (eventId != null) predicates.add(builder.equal(root.get("event").get("id"), eventId));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        var page = collections.findAll(filter, pageable);
        return PageDTO.of(page, totals.collections(page.getContent()));
    }

    private void contains(List<Predicate> predicates, Root<?> root, CriteriaBuilder builder,
            String field, String value) {
        if (value != null && !value.isBlank()) {
            String escaped = value.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
            predicates.add(builder.like(builder.lower(root.get(field)), "%" + escaped + "%", '!'));
        }
    }

    private void validateText(String field, String value) {
        if (value != null && value.length() > 255) {
            throw new FieldValidationException(field, "O filtro deve ter no máximo 255 caracteres.");
        }
    }

    private <T extends Comparable<? super T>> void validateRange(T from, T to) {
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw new FieldValidationException("to", "O fim do período deve ser igual ou posterior ao início.");
        }
    }

    private void validateId(String field, Long id) {
        if (id != null && id <= 0) throw new FieldValidationException(field, "Informe um ID positivo.");
    }
}
