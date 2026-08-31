package com.raphael.beachcleanup.services;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;

@Service
public class CleanupEventService {
	
	@Autowired
	private CleanupEventRepository repository;
	
	public List<CleanupEvent> findAll() {
		return repository.findAll();
	}
	
	public CleanupEvent findById(Long id) {
		Optional<CleanupEvent> obj = repository.findById(id);
		return obj.get();
	}
}
