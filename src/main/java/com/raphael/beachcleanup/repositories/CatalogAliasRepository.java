package com.raphael.beachcleanup.repositories;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.raphael.beachcleanup.entities.CatalogAlias;

public interface CatalogAliasRepository extends JpaRepository<CatalogAlias, CatalogAlias.Key> {
    List<CatalogAlias> findByEntryId(Long id);
    List<CatalogAlias> findByIdKind(String kind);
}
