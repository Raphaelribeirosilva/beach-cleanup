package com.raphael.beachcleanup.resources;

import java.net.URI;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.raphael.beachcleanup.entities.Volunteer;
import com.raphael.beachcleanup.services.VolunteerService;

@RestController
@RequestMapping(value = "/volunteers")
public class VolunteerResource {

	@Autowired
	private VolunteerService service;
	
	@GetMapping
	public ResponseEntity<List<Volunteer>> findAll() {
		List<Volunteer> list = service.findAll();
		return ResponseEntity.ok().body(list);
	}
	
	@GetMapping(value = "/{id}")
	public ResponseEntity<Volunteer> findById(@PathVariable Long id) {
		Volunteer obj = service.findById(id);
		return ResponseEntity.ok().body(obj);
	}
	
	@PostMapping
	public ResponseEntity<Volunteer> insert(@PathVariable Volunteer obj){
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
	public ResponseEntity<Volunteer> update(@PathVariable Long id, @RequestBody Volunteer obj){
		obj = service.update(id, obj);
		return ResponseEntity.ok().body(obj);
	}
}
