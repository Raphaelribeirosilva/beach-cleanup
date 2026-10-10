package com.raphael.beachcleanup.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.raphael.beachcleanup.entities.CleanupEvent;

public interface CleanupEventRepository extends JpaRepository<CleanupEvent, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<CleanupEvent> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from CleanupEvent e where e.id = :id")
    java.util.Optional<CleanupEvent> findByIdForUpdate(@Param("id") Long id);
}
