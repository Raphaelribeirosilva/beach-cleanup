package com.raphael.beachcleanup.entities;

import java.math.BigDecimal;
import jakarta.persistence.*;

/** Immutable V7 snapshot, deliberately independent of later edits/deletions of collections. */
@Entity
@Table(name = "tb_weight_conversion_audit")
@org.hibernate.annotations.Immutable
public class WeightConversionAudit {
    @Id @Column(name = "collection_id") private Long collectionId;
    @Column(name = "volunteer_id") private Long volunteerId;
    @Column(name = "original_weight", length = 64) private String originalWeight;
    @Column(name = "rounded_weight", precision = 38, scale = 3) private BigDecimal roundedWeight;
    @Column(name = "delta_weight", length = 96) private String deltaWeight;
    protected WeightConversionAudit() {}
}
