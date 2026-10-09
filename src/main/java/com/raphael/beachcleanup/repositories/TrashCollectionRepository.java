package com.raphael.beachcleanup.repositories;

import org.springframework.data.jpa.repository.JpaRepository;

import com.raphael.beachcleanup.entities.TrashCollection;

public interface TrashCollectionRepository extends JpaRepository<TrashCollection, Long>{
    java.util.List<TrashCollection> findAllByVolunteerId(Long userId);
}
