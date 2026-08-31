package com.raphael.beachcleanup.services;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;

@Service
public class TrashCollectionService {
	
	@Autowired
	private TrashCollectionRepository repository;
	
	public List<TrashCollection> findAll() {
		return repository.findAll();
	}
	
	public TrashCollection findById(Long id) {
		Optional<TrashCollection> obj = repository.findById(id);
		return obj.get();
	}
}
