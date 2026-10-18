package com.raphael.beachcleanup.resources;

import java.net.URI;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.dto.CleanupEventInputDTO;
import com.raphael.beachcleanup.dto.CleanupEventDTO;
import com.raphael.beachcleanup.services.CleanupEventService;

@RestController
@RequestMapping(value = "/cleanupEvent")
public class CleanupEventResource {
	
	@Autowired
	private CleanupEventService service;
	
	@GetMapping
	public ResponseEntity<List<CleanupEventDTO>> findAll() {
		List<CleanupEvent> list = service.findAll();
		return ResponseEntity.ok().body(list.stream().map(CleanupEventDTO::new).toList());
	}
	
	@GetMapping(value = "/{id}")
	public ResponseEntity<CleanupEventDTO> findById(@PathVariable Long id) {
		CleanupEvent obj = service.findById(id);
		return ResponseEntity.ok().body(new CleanupEventDTO(obj));
	}
	
	@PostMapping
    @PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<CleanupEventDTO> insert(@Valid @RequestBody CleanupEventInputDTO dto){
		CleanupEvent obj = service.insert(dto.toEntity());
		URI uri = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
			.buildAndExpand(obj.getId()).toUri();
		return ResponseEntity.created(uri).body(new CleanupEventDTO(obj));
	}
	
	@DeleteMapping(value = "/{id}")
    @PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Void> delete(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestParam(required = false) Long version){
		service.delete(id, version);
		return ResponseEntity.noContent().build();
	}
	
	@PutMapping(value = "/{id}")
    @PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<CleanupEventDTO> update(@PathVariable Long id, @Valid @RequestBody CleanupEventInputDTO dto){
		CleanupEvent obj = service.update(id, dto.toEntity(), dto.version());
		return ResponseEntity.ok().body(new CleanupEventDTO(obj));
	}
}
