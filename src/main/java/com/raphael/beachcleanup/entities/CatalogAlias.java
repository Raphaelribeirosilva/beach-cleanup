package com.raphael.beachcleanup.entities;

import java.io.Serializable;
import jakarta.persistence.*;

@Entity
@Table(name = "tb_catalog_alias")
public class CatalogAlias {
    @Embeddable
    public record Key(@Column(length = 16) String kind, @Column(name = "normalized_name") String name)
            implements Serializable {}

    @EmbeddedId
    private Key id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private CatalogEntry entry;

    public CatalogAlias() {}
    public CatalogAlias(CatalogEntry entry, String normalizedName) {
        this.id = new Key(entry.getKind(), normalizedName);
        this.entry = entry;
    }
    public Key getId() { return id; }
    public CatalogEntry getEntry() { return entry; }
    public void setEntry(CatalogEntry entry) { this.entry = entry; }
}
