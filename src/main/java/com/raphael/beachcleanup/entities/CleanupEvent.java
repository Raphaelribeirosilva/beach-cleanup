package com.raphael.beachcleanup.entities;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Version;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_cleanupEvent")
public class CleanupEvent implements Serializable{
	
	private static final long serialVersionUID = 1L;
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

    @JsonIgnore
    @Version
    @Column(nullable = false)
    private Long rowVersion;
	
	private String title;
	private String location;
	private LocalDateTime dateTime;
	private String status;
	
	@JsonIgnore
    @ManyToMany(mappedBy = "events")
	private Set<User> users = new HashSet<>();
	
	@JsonIgnore
    @OneToMany(mappedBy = "event")
	private List<TrashCollection> collection = new ArrayList<>();
	
	public CleanupEvent() {}
	
	public CleanupEvent(Long id, String title, String location, LocalDateTime dateTime, String status) {
		super();
		this.id = id;
		this.title = title;
		this.location = location;
		this.dateTime = dateTime;
		this.status = status;
	}

	public Long getRowVersion() {
        return rowVersion;
    }

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	public LocalDateTime getDateTime() {
		return dateTime;
	}

	public void setDateTime(LocalDateTime dateTime) {
		this.dateTime = dateTime;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}
	
	

	public Set<User> getUsers() {
		return users;
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
		CleanupEvent other = (CleanupEvent) obj;
		return Objects.equals(id, other.id);
	}
	
	
}
