package com.raphael.beachcleanup.resources;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.raphael.beachcleanup.dto.CleanupEventDTO;
import com.raphael.beachcleanup.security.AccessPolicy;
import com.raphael.beachcleanup.services.EventRegistrationService;

@RestController
@RequestMapping("/users/me/events")
public class EventRegistrationResource {
    private final EventRegistrationService service;
    private final AccessPolicy access;

    public EventRegistrationResource(EventRegistrationService service, AccessPolicy access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ResponseEntity<List<CleanupEventDTO>> findMyEvents() {
        return ResponseEntity.ok(service.findMyEvents(access.currentId()));
    }

    @GetMapping("/search")
    public ResponseEntity<com.raphael.beachcleanup.dto.RegistrationPageDTO> search(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size,
            @org.springframework.web.bind.annotation.RequestParam(required = false) List<Long> eventIds) {
        return ResponseEntity.ok(service.findPage(access.currentId(), eventIds == null ? List.of() : eventIds,
            com.raphael.beachcleanup.services.SearchPagination.of(page,size,"id","asc",java.util.Set.of("id"))));
    }

    @PutMapping("/{eventId}")
    public ResponseEntity<Void> register(@PathVariable Long eventId) {
        service.register(access.currentId(), eventId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> cancel(@PathVariable Long eventId) {
        service.cancel(access.currentId(), eventId);
        return ResponseEntity.noContent().build();
    }
}
