package com.raphael.beachcleanup.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

import com.raphael.beachcleanup.entities.TrashCollection;

public interface TrashCollectionRepository extends JpaRepository<TrashCollection, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<TrashCollection> {
    @Override
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"volunteer", "event"})
    org.springframework.data.domain.Page<TrashCollection> findAll(
        org.springframework.data.jpa.domain.Specification<TrashCollection> spec,
        org.springframework.data.domain.Pageable pageable);
    java.util.List<TrashCollection> findAllByVolunteerId(Long userId);

    interface CollectedTotal {
        Long getUserId();
        Double getTotalKg();
    }

    @Query("""
        select c.volunteer.id as userId, sum(c.weightKg) as totalKg
        from TrashCollection c
        where c.volunteer.id in :userIds
        group by c.volunteer.id
        """)
    List<CollectedTotal> sumWeightByUserIds(@Param("userIds") Collection<Long> userIds);
}
