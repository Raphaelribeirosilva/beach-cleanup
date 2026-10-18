package com.raphael.beachcleanup.resources;

import java.time.Instant;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.raphael.beachcleanup.services.AuditService;
import com.raphael.beachcleanup.entities.AuditEntry;
import com.raphael.beachcleanup.dto.PageDTO;

@RestController
@RequestMapping("/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditResource {
    private final AuditService service;
    public AuditResource(AuditService service) { this.service = service; }
    @GetMapping
    public PageDTO<AuditEntry> search(@RequestParam(required = false) String resource,
            @RequestParam(required = false) Long resourceId, @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(resource, resourceId, actorId, action, from, to, page, size);
    }
}
