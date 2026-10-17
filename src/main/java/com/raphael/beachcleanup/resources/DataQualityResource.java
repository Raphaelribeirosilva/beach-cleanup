package com.raphael.beachcleanup.resources;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.raphael.beachcleanup.dto.PageDTO;
import com.raphael.beachcleanup.services.DataQualityService;

@RestController
@RequestMapping("/admin/data-quality")
@PreAuthorize("hasRole('ADMIN')")
public class DataQualityResource {
    private final DataQualityService service;
    public DataQualityResource(DataQualityService service) { this.service = service; }
    @GetMapping
    public PageDTO<DataQualityService.Issue> issues(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) { return service.issues(page, size); }
    @GetMapping("/rounding")
    public PageDTO<DataQualityService.Rounding> rounding(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) { return service.rounding(page, size); }
}
