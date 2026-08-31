package com.raphael.beachcleanup.resources;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
