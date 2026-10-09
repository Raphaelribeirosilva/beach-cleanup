package com.raphael.beachcleanup.repositories;

import org.springframework.data.jpa.repository.JpaRepository;

import com.raphael.beachcleanup.entities.Volunteer;

public interface VolunteerRepository extends JpaRepository<Volunteer, Long>{
    java.util.Optional<Volunteer> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByEmailAndIdNot(String email, Long id);
}
