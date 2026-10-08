package com.raphael.beachcleanup.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class VolunteerInsertDTO {
	 
	@NotBlank(message = "O nome é obrigatório")
	private String name;
	
	@NotBlank(message = "O e-mail é obrigatório")
	@Email(message = "Formato de e-mail inválido")
	private String email;
	
	@NotBlank(message = "A senha é obrigatória")
	private String password;
	
	public VolunteerInsertDTO() {}

	public VolunteerInsertDTO(String name,String email,String passoword) {
		super();
		this.name = name;
		this.email = email;
		this.password = passoword;
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

	public void setPassoword(String passoword) {
		this.password = passoword;
	}
	
	
}
