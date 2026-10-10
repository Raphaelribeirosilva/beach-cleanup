package com.raphael.beachcleanup.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.raphael.beachcleanup.entities.User;

public interface UserRepository extends JpaRepository<User, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<User> {
    java.util.Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByEmailAndIdNot(String email, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    java.util.Optional<User> findByIdForRegistration(@Param("id") Long id);
}
