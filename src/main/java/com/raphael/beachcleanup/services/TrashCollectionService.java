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
	
	public TrashCollection insert(TrashCollection obj) {
		return repository.save(obj);
	}
	
	public void delete(Long id){
		repository.deleteById(id);
	}
	
	public TrashCollection update(Long id, TrashCollection obj) {
		TrashCollection entity = repository.getReferenceById(id);
		updateData(entity, obj);
		return repository.save(entity);
	}
	
	public void updateData(TrashCollection entity, TrashCollection obj) {
		entity.setWeithKg(obj.getWeithKg());
	    entity.setBeach(obj.getBeach());
	    entity.setMaterialType(obj.getMaterialType());
	}
}
