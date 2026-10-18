package com.raphael.beachcleanup.resources;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.raphael.beachcleanup.services.ImpactReportService;

@RestController
@RequestMapping("/reports")
public class ImpactReportResource {
    private final ImpactReportService service;
    public ImpactReportResource(ImpactReportService service) { this.service = service; }

    @GetMapping("/impact")
    public ImpactReportService.Report impact(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long beachId, @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long eventId, @RequestParam(defaultValue = "beach") String groupBy) {
        return service.impact(from, to, beachId, materialId, eventId, groupBy);
    }

    @GetMapping("/participation")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ImpactReportService.Participation> participation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long beachId, @RequestParam(required = false) Long eventId) {
        return service.participation(from, to, beachId, eventId);
    }
}
