package com.raphael.beachcleanup.resources;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.services.CleanupEventService;

@RestController
@RequestMapping(value = "/cleanupEvent")
public class CleanupEventResource {
	
	@Autowired
	private CleanupEventService service;
	
	public ResponseEntity<List<CleanupEvent>> findAll() {
		List<CleanupEvent> list = service.findAll();
		return ResponseEntity.ok().body(list);
	}
	
	public ResponseEntity<CleanupEvent> findById(@PathVariable Long id) {
		CleanupEvent obj = service.findById(id);
		return ResponseEntity.ok().body(obj);
	}
}
