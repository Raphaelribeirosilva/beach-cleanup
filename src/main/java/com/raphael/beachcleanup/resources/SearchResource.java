package com.raphael.beachcleanup.resources;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.raphael.beachcleanup.dto.*;
import com.raphael.beachcleanup.services.SearchPagination;
import com.raphael.beachcleanup.services.SearchService;

@RestController
public class SearchResource {
    private final SearchService service;

    public SearchResource(SearchService service) {
        this.service = service;
    }

    @GetMapping("/users/search")
    @PreAuthorize("hasRole('ADMIN')")
    public PageDTO<UserDTO> users(@RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy, @RequestParam(defaultValue = "asc") String direction) {
        return service.users(name, email, SearchPagination.of(page, size, sortBy, direction, Set.of("id", "name", "email")));
    }

    @GetMapping("/cleanupEvent/search")
    public PageDTO<CleanupEventDTO> events(@RequestParam(required = false) String location,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy, @RequestParam(defaultValue = "asc") String direction) {
        return service.events(location, status, from, to,
            SearchPagination.of(page, size, sortBy, direction, Set.of("id", "title", "location", "dateTime", "status")));
    }

    @GetMapping("/trashCollection/search")
    public PageDTO<TrashCollectionDTO> collections(@RequestParam(required = false) String beach,
            @RequestParam(required = false) String materialType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long volunteerId, @RequestParam(required = false) Long eventId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy, @RequestParam(defaultValue = "asc") String direction) {
        return service.collections(beach, materialType, from, to, volunteerId, eventId,
            SearchPagination.of(page, size, sortBy, direction,
                Set.of("id", "beach", "materialType", "weightKg", "registrationDate")));
    }
}
