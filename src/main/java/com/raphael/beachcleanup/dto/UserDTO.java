package com.raphael.beachcleanup.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import com.raphael.beachcleanup.entities.User;

public class UserDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long version;
    private String name;
    private String email;
    private boolean emailVerified;
    public boolean isEmailVerified() { return emailVerified; }
    private BigDecimal totalCollectedKg;

    public UserDTO() {
    }

    public UserDTO(User entity, BigDecimal totalCollectedKg) {
        this.id = entity.getId();
        this.version = entity.getRowVersion();
        this.name = entity.getName();
        this.email = entity.getEmail();
        this.emailVerified = entity.isEmailVerified();
        this.totalCollectedKg = totalCollectedKg;
    }
    
    public Long getVersion() {
        return version;
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

    public BigDecimal getTotalCollectedKg() {
        return totalCollectedKg;
    }

    public void setTotalCollectedKg(BigDecimal totalCollectedKg) {
        this.totalCollectedKg = totalCollectedKg;
    }
}
