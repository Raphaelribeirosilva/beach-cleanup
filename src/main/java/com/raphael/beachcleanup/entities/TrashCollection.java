package com.raphael.beachcleanup.entities;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "tb_trash_collection")
public class TrashCollection implements Serializable{
	
	private static final long serialVersionUID = 1L;
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

    @JsonIgnore
    @Version
    @Column(nullable = false)
    private Long rowVersion;
	
	private String beach;
	private String materialType;
    @Column(name = "weight_kg")
	private Double weightKg;
    @Column(name = "registration_date")
	private LocalDate registrationDate;
	
	@ManyToOne
    @JoinColumn(name = "volunteer_id")
	private User volunteer;
	
	@ManyToOne
    @JoinColumn(name = "event_id")
	private CleanupEvent event;
	
	public TrashCollection() {}

	public TrashCollection(Long id, String beach, String materialType, Double weightKg, LocalDate registrationDate) {
		super();
		this.id = id;
		this.beach = beach;
		this.materialType = materialType;
		this.weightKg = weightKg;
		this.registrationDate = registrationDate;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getBeach() {
		return beach;
	}

	public void setBeach(String beach) {
		this.beach = beach;
	}

	public String getMaterialType() {
		return materialType;
	}

	public void setMaterialType(String materialType) {
		this.materialType = materialType;
	}

	public Double getWeightKg() {
		return weightKg;
	}

	public void setWeightKg(Double weightKg) {
		this.weightKg = weightKg;
	}

	public LocalDate getRegistrationDate() {
		return registrationDate;
	}

	public void setRegistrationDate(LocalDate registrationDate) {
		this.registrationDate = registrationDate;
	}
	

	public User getVolunteer() {
		return volunteer;
	}

	public void setVolunteer(User volunteer) {
		this.volunteer = volunteer;
	}

	public CleanupEvent getEvent() {
		return event;
	}

	public void setEvent(CleanupEvent event) {
		this.event = event;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		TrashCollection other = (TrashCollection) obj;
		return Objects.equals(id, other.id);
	}
	
	
}
