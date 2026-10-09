package com.raphael.beachcleanup.dto;

import java.io.Serializable;
import com.raphael.beachcleanup.entities.User;

public class UserDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String email;
    private Double totalCollectedKg;

    public UserDTO() {
    }

    public UserDTO(User entity) {
        this.id = entity.getId();
        this.name = entity.getName();
        this.email = entity.getEmail();
        this.totalCollectedKg = entity.getTotalCollectedKg();
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

    public Double getTotalCollectedKg() {
        return totalCollectedKg;
    }

    public void setTotalCollectedKg(Double totalCollectedKg) {
        this.totalCollectedKg = totalCollectedKg;
    }
}