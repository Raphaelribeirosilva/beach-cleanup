package com.raphael.beachcleanup.resources;

import java.net.URI;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.services.TrashCollectionService;

@RestController
@RequestMapping(value = "/trashCollection")
public class TrashCollectionResource {

	@Autowired
	private TrashCollectionService service;
	
	public ResponseEntity<List<TrashCollection>> findAll() {
		List<TrashCollection> list = service.findAll();
		return ResponseEntity.ok().body(list);
	}
	
	public ResponseEntity<TrashCollection> findById(@PathVariable Long id) {
		TrashCollection obj = service.findById(id);
		return ResponseEntity.ok().body(obj);
	}
	
	@PostMapping
	public ResponseEntity<TrashCollection> insert(@PathVariable TrashCollection obj){
		obj = service.insert(obj);
		URI uri = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
			.buildAndExpand(obj.getId()).toUri();
		return ResponseEntity.ok().body(obj);
	}
	
	@DeleteMapping(value = "/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id){
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
	
	@PostMapping(value = "/{id}")
	public ResponseEntity<TrashCollection> update(@PathVariable Long id, @RequestBody TrashCollection obj){
		obj = service.update(id, obj);
		return ResponseEntity.ok().body(obj);
	}
}
