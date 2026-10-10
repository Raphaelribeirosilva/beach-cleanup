package com.raphael.beachcleanup.resources;

import java.net.URI;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.raphael.beachcleanup.dto.UserDTO;
import com.raphael.beachcleanup.dto.UserInsertDTO;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.dto.UserUpdateDTO;
import com.raphael.beachcleanup.services.UserService;
import com.raphael.beachcleanup.services.CollectedTotalsService;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping(value = "/users")
public class UserResource {

	@Autowired
	private UserService service;

    @Autowired
    private CollectedTotalsService totals;
	
	@GetMapping
    @PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<List<UserDTO>> findAll() {
		
		List<User> list = service.findAll();
		List<UserDTO> listDto = totals.users(list);
		return ResponseEntity.ok().body(listDto);
	}
	
	@GetMapping(value = "/{id}")
    @PreAuthorize("@accessPolicy.canAccessUser(#id)")
	public ResponseEntity<UserDTO> findById(@PathVariable Long id) {
		User obj = service.findById(id);
		return ResponseEntity.ok().body(totals.user(obj));
	}
	
	@PostMapping
    public ResponseEntity<UserDTO> insert(@Valid @RequestBody UserInsertDTO dto) {
      
        User entity = new User(null, dto.getName(), dto.getEmail(), dto.getPassword(), 0.0);
        entity = service.insert(entity);
        URI uri = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
            .buildAndExpand(entity.getId()).toUri();
        return ResponseEntity.created(uri).body(totals.user(entity));
    }
	
	@DeleteMapping(value = "/{id}")
    @PreAuthorize("@accessPolicy.canAccessUser(#id)")
	public ResponseEntity<Void> delete(@PathVariable Long id){
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
	
	@PutMapping(value = "/{id}")
    @PreAuthorize("@accessPolicy.canAccessUser(#id)")
	public ResponseEntity<UserDTO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO dto){
		User obj = service.update(id, dto.toEntity());
		return ResponseEntity.ok().body(totals.user(obj));
	}
}
