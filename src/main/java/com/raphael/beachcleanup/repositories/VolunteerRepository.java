package com.raphael.beachcleanup.repositories;

import org.springframework.data.jpa.repository.JpaRepository;

import com.raphael.beachcleanup.entities.Volunteer;

public interface VolunteerRepository extends JpaRepository<Volunteer, Long>{

}
