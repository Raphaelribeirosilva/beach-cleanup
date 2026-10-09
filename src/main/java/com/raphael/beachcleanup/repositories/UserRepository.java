package com.raphael.beachcleanup.repositories;

import org.springframework.data.jpa.repository.JpaRepository;

import com.raphael.beachcleanup.entities.User;

public interface UserRepository extends JpaRepository<User, Long>{
    java.util.Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByEmailAndIdNot(String email, Long id);
}
