package com.raphael.beachcleanup.repositories;

import org.springframework.data.jpa.repository.JpaRepository;

import com.raphael.beachcleanup.entities.CleanupEvent;

public interface CleanupEventRepository extends JpaRepository<CleanupEvent, Long>{

}
