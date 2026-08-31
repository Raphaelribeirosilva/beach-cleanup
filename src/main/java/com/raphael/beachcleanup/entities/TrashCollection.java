package com.raphael.beachcleanup.entities;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_trashCollection")
public class TrashCollection implements Serializable{
	
	private static final long serialVersionUID = 1L;
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	private String beach;
	private String materialType;
	private Double weithKg;
	private LocalDate regitrationDate;
	
	public TrashCollection() {}

	public TrashCollection(Long id, String beach, String materialType, Double weithKg, LocalDate regitrationDate) {
		super();
		this.id = id;
		this.beach = beach;
		this.materialType = materialType;
		this.weithKg = weithKg;
		this.regitrationDate = regitrationDate;
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

	public Double getWeithKg() {
		return weithKg;
	}

	public void setWeithKg(Double weithKg) {
		this.weithKg = weithKg;
	}

	public LocalDate getRegitrationDate() {
		return regitrationDate;
	}

	public void setRegitrationDate(LocalDate regitrationDate) {
		this.regitrationDate = regitrationDate;
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
