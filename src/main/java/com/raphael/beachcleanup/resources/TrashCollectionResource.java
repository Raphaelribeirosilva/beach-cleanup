package com.raphael.beachcleanup.resources;

import java.net.URI;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import com.raphael.beachcleanup.security.AccessPolicy;
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

import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.dto.TrashCollectionInputDTO;
import com.raphael.beachcleanup.dto.TrashCollectionDTO;
import com.raphael.beachcleanup.services.TrashCollectionService;

@RestController
@RequestMapping(value = "/trashCollection")
public class TrashCollectionResource {

	@Autowired
	private TrashCollectionService service;

    @Autowired
    private AccessPolicy accessPolicy;
	
	@GetMapping
	public ResponseEntity<List<TrashCollectionDTO>> findAll() {
		List<TrashCollection> list = accessPolicy.visibleCollections();
		return ResponseEntity.ok().body(list.stream().map(TrashCollectionDTO::new).toList());
	}
	
	@GetMapping(value = "/{id}")
    @PreAuthorize("@accessPolicy.canAccessCollection(#id)")
	public ResponseEntity<TrashCollectionDTO> findById(@PathVariable Long id) {
		TrashCollection obj = service.findById(id);
		return ResponseEntity.ok().body(new TrashCollectionDTO(obj));
	}
	
	@PostMapping
	public ResponseEntity<TrashCollectionDTO> insert(@Valid @RequestBody TrashCollectionInputDTO dto){
		TrashCollection obj = dto.toEntity();
        accessPolicy.bindCollectionOwner(obj);
        obj = service.insert(obj);
		URI uri = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
			.buildAndExpand(obj.getId()).toUri();
		return ResponseEntity.created(uri).body(new TrashCollectionDTO(obj));
	}
	
	@DeleteMapping(value = "/{id}")
    @PreAuthorize("@accessPolicy.canAccessCollection(#id)")
	public ResponseEntity<Void> delete(@PathVariable Long id){
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
	
	@PutMapping(value = "/{id}")
    @PreAuthorize("@accessPolicy.canAccessCollection(#id)")
	public ResponseEntity<TrashCollectionDTO> update(@PathVariable Long id, @Valid @RequestBody TrashCollectionInputDTO dto){
		TrashCollection obj = dto.toEntity();
        accessPolicy.bindCollectionOwner(obj);
        obj = service.update(id, obj);
		return ResponseEntity.ok().body(new TrashCollectionDTO(obj));
	}
}
