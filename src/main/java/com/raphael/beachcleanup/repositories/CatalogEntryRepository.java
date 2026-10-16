package com.raphael.beachcleanup.repositories;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.raphael.beachcleanup.entities.CatalogEntry;

public interface CatalogEntryRepository extends JpaRepository<CatalogEntry, Long> {
    List<CatalogEntry> findByKindAndMergedIntoIsNullOrderByNameAscIdAsc(String kind);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CatalogEntry c where c.id = :id")
    Optional<CatalogEntry> lockById(@Param("id") Long id);
}
