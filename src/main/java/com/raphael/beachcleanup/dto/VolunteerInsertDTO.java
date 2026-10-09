package com.raphael.beachcleanup.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonProperty;

public class VolunteerInsertDTO {
	 
	@NotBlank(message = "O nome é obrigatório")
	@Size(max = 255, message = "O nome deve ter no máximo 255 caracteres")
	private String name;
	
	@NotBlank(message = "O e-mail é obrigatório")
	@Email(message = "Formato de e-mail inválido")
	@Size(max = 255, message = "O e-mail deve ter no máximo 255 caracteres")
	private String email;
	
	@NotBlank(message = "A senha é obrigatória")
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private String password;
	
	public VolunteerInsertDTO() {}

	public VolunteerInsertDTO(String name,String email,String password) {
		super();
		this.name = name;
		this.email = email;
		this.password = password;
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

	public void setPassword(String password) {
		this.password = password;
	}
	
	
}
