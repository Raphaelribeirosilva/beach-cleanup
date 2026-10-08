package com.raphael.beachcleanup.services;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.raphael.beachcleanup.entities.Volunteer;
import com.raphael.beachcleanup.exceptions.ResourceNotFoundException;
import com.raphael.beachcleanup.repositories.VolunteerRepository;

@Service
public class VolunteerService {
	
	@Autowired
	private VolunteerRepository repository;
	
	public List<Volunteer> findAll() {
		return repository.findAll();
	}
	
	public Volunteer findById(Long id) {
		Optional<Volunteer> obj = repository.findById(id);
		return obj.orElseThrow(() -> new ResourceNotFoundException(id));
	}
	
	public Volunteer insert(Volunteer obj) {
		return repository.save(obj);
	}
	
	public void delete(Long id){
		repository.deleteById(id);
	}
	
	public Volunteer update(Long id, Volunteer obj) {
		Volunteer entity = repository.getReferenceById(id);
		updateData(entity, obj);
		return repository.save(entity);
	}
	
	public void updateData(Volunteer entity, Volunteer obj) {
		entity.setName(obj.getName());
		entity.setName(obj.getEmail());
	}
}
