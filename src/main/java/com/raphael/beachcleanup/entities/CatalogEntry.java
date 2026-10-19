package com.raphael.beachcleanup.entities;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import jakarta.persistence.*;

@Entity
@Table(name = "tb_catalog_entry")
public class CatalogEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 16)
    private String kind;
    @Column(nullable = false)
    private String name;
    @Column(length = 2)
    private String state;
    @Column(length = 255)
    private String city;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_into_id")
    private CatalogEntry mergedInto;

    public CatalogEntry() {}
    public CatalogEntry(String kind, String name) {
        this.kind = kind;
        this.name = name;
        touch();
    }
    public static CatalogEntry reference(Long id) {
        CatalogEntry entry = new CatalogEntry();
        entry.id = id;
        return entry;
    }
    public Long getId() { return id; }
    public String getKind() { return kind; }
    public String getName() { return name; }
    public String getState() { return state; }
    public String getCity() { return city; }
    public void setGeography(String state, String city) { this.state = state; this.city = city; touch(); }
    public Long getVersion() { return version; }
    public CatalogEntry getMergedInto() { return mergedInto; }
    public void setName(String name) { this.name = name; touch(); }
    public void mergeInto(CatalogEntry target) { this.mergedInto = target; touch(); }
    public void touch() { updatedAt = LocalDateTime.now(ZoneOffset.UTC); }
}
