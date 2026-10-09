package com.raphael.beachcleanup.entities;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;

@Entity
@Table(name = "tb_volunteer")
public class Volunteer implements Serializable{
	
	private static final long serialVersionUID = 1L;
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	private String name;
	@Column(nullable = false, unique = true)
	private String email;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private VolunteerRole role = VolunteerRole.VOLUNTEER;
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private String password;
	private Double totalTrashCollecting;
	
	@ManyToMany
    @JoinTable(
        name = "tb_event_volunteer", 
        joinColumns = @JoinColumn(name = "volunteer_id"), 
        inverseJoinColumns = @JoinColumn(name = "event_id")
    )
	private Set<CleanupEvent> events = new HashSet<>();
	
	@JsonIgnore
    @OneToMany(mappedBy = "volunteer")
	private List<TrashCollection> collection = new ArrayList<>();
	
	public Volunteer() {}

	public Volunteer(Long id, String name, String email, String password, Double totalTrashCollecting) {
		super();
		this.id = id;
		this.name = name;
		this.email = email;
		this.password = password;
		this.totalTrashCollecting = totalTrashCollecting;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPassword() {
		return password;
	}

    public VolunteerRole getRole() {
        return role;
    }

    public void setRole(VolunteerRole role) {
        this.role = role;
    }

	public void setPassword(String password) {
		this.password = password;
	}

	public Double getTotalTrashCollecting() {
		return totalTrashCollecting;
	}

	public void setTotalTrashCollecting(Double totalTrashCollecting) {
		this.totalTrashCollecting = totalTrashCollecting;
	}
	
	public Set<CleanupEvent> getEvents() {
		return events;
	}

	public List<TrashCollection> getCollection() {
		return collection;
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
		Volunteer other = (Volunteer) obj;
		return Objects.equals(id, other.id);
	}
}
