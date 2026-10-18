package com.raphael.beachcleanup.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.raphael.beachcleanup.entities.CleanupEvent;

public interface CleanupEventRepository extends JpaRepository<CleanupEvent, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<CleanupEvent> {
    @Override
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "beachReference")
    org.springframework.data.domain.Page<CleanupEvent> findAll(
        org.springframework.data.jpa.domain.Specification<CleanupEvent> spec, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "beachReference")
    @Query("select e from CleanupEvent e join e.users u where u.id=:userId")
    org.springframework.data.domain.Page<CleanupEvent> registrations(@Param("userId") Long userId,
        org.springframework.data.domain.Pageable pageable);

    @Query("select e.id from CleanupEvent e join e.users u where u.id=:userId and e.id in :ids order by e.id")
    java.util.List<Long> registeredIds(@Param("userId") Long userId, @Param("ids") java.util.List<Long> ids);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from CleanupEvent e where e.id = :id")
    java.util.Optional<CleanupEvent> findByIdForUpdate(@Param("id") Long id);
}
