package com.raphael.beachcleanup.resources;

import java.net.URI;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.raphael.beachcleanup.dto.*;
import com.raphael.beachcleanup.services.CatalogService;

@RestController
@RequestMapping("/catalog")
public class CatalogResource {
    private final CatalogService service;
    public CatalogResource(CatalogService service) { this.service = service; }

    @GetMapping("/{kind}")
    public List<CatalogDTO> list(@PathVariable String kind) { return service.list(kind); }

    @PostMapping("/{kind}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CatalogDTO> create(@PathVariable String kind, @Valid @RequestBody CatalogInputDTO input) {
        CatalogDTO result = service.create(kind, input);
        return ResponseEntity.created(URI.create("/catalog/" + kind)).body(result);
    }

    @PutMapping("/{kind}/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogDTO update(@PathVariable String kind, @PathVariable Long id, @Valid @RequestBody CatalogInputDTO input) {
        return service.update(kind, id, input);
    }

    @PostMapping("/{kind}/{id}/merge")
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogDTO merge(@PathVariable String kind, @PathVariable Long id, @Valid @RequestBody CatalogMergeDTO input) {
        return service.merge(kind, id, input);
    }
}
