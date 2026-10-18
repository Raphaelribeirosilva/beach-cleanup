package com.raphael.beachcleanup.services;

import java.time.Clock;
import java.time.Instant;
import com.raphael.beachcleanup.entities.AuditEntry;
import com.raphael.beachcleanup.dto.PageDTO;
import com.raphael.beachcleanup.repositories.AuditEntryRepository;
import com.raphael.beachcleanup.exceptions.FieldValidationException;
import com.raphael.beachcleanup.exceptions.EventStateConflictException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuditService {
    private final AuditEntryRepository entries;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    public AuditService(AuditEntryRepository entries, Clock clock) { this.entries = entries; this.clock = clock; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String resource, Long id, String action, Long oldUser, Long newUser,
            Long oldEvent, Long newEvent, Long target, String oldState, String newState) {
        var entry = new AuditEntry();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        entry.actorRole = "SYSTEM";
        if (auth != null && auth.isAuthenticated() && !auth.getName().equals("anonymousUser")) {
            entry.actorRole = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))
                ? "ADMIN" : "VOLUNTEER";
            try { entry.actorId = Long.valueOf(auth.getName()); } catch (NumberFormatException ignored) {
                // Non-numeric principals exist in internal tests; never retain arbitrary principal text.
            }
        }
        entry.occurredAt = clock.instant(); entry.resource = resource; entry.resourceId = id; entry.action = action;
        entry.previousUserId = oldUser; entry.nextUserId = newUser;
        entry.previousEventId = oldEvent; entry.nextEventId = newEvent; entry.targetId = target;
        entry.previousState = oldState; entry.nextState = newState;
        entries.save(entry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNoHistory(String resource, Long id) {
        // A locking/current read also sees evidence committed after a MySQL RR snapshot was opened.
        String predicate = resource.equals("USER")
            ? "(resource='USER' and resource_id=?) or actor_id=? or previous_user_id=? or next_user_id=?"
            : "(resource='EVENT' and resource_id=?) or previous_event_id=? or next_event_id=?";
        Object[] ids = resource.equals("USER") ? new Object[]{id,id,id,id} : new Object[]{id,id,id};
        if (!jdbc.queryForList("select id from tb_audit_entry where " + predicate + " limit 1 for update", ids).isEmpty()) throw new EventStateConflictException(
            "Este registro possui histórico e deve ser preservado. A exclusão não é permitida.");
    }

    @Transactional(readOnly = true)
    public PageDTO<AuditEntry> search(String resource, Long resourceId, Long actorId, String action,
            Instant from, Instant to, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || page > 1000000 || (from != null && to != null && from.isAfter(to)))
            throw new FieldValidationException("filters", "Confira o período e a paginação (size de 1 a 100).");
        Specification<AuditEntry> spec = (root, query, cb) -> {
            var filters = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (resource != null) filters.add(cb.equal(root.get("resource"), resource));
            if (resourceId != null) filters.add(cb.equal(root.get("resourceId"), resourceId));
            if (actorId != null) filters.add(cb.equal(root.get("actorId"), actorId));
            if (action != null) filters.add(cb.equal(root.get("action"), action));
            if (from != null) filters.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) filters.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            return cb.and(filters.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = entries.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
        return PageDTO.of(result, result.getContent());
    }
}
