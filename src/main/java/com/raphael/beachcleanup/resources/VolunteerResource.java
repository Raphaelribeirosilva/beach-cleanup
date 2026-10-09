package com.raphael.beachcleanup.resources;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

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

import com.raphael.beachcleanup.dto.VolunteerDTO;
import com.raphael.beachcleanup.dto.VolunteerInsertDTO;
import com.raphael.beachcleanup.entities.Volunteer;
import com.raphael.beachcleanup.dto.VolunteerUpdateDTO;
import com.raphael.beachcleanup.services.VolunteerService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(value = "/volunteers")
public class VolunteerResource {

	@Autowired
	private VolunteerService service;
	
	@GetMapping
	public ResponseEntity<List<VolunteerDTO>> findAll() {
		
		List<Volunteer> list = service.findAll();
		List<VolunteerDTO> listDto = list.stream().map(x -> new VolunteerDTO(x)).collect(Collectors.toList());
		return ResponseEntity.ok().body(listDto);
	}
	
	@GetMapping(value = "/{id}")
	public ResponseEntity<VolunteerDTO> findById(@PathVariable Long id) {
		Volunteer obj = service.findById(id);
		return ResponseEntity.ok().body(new VolunteerDTO(obj));
	}
	
	@PostMapping
    public ResponseEntity<VolunteerDTO> insert(@Valid @RequestBody VolunteerInsertDTO dto) {
      
        Volunteer entity = new Volunteer(null, dto.getName(), dto.getEmail(), dto.getPassword(), 0.0);
        entity = service.insert(entity);
        URI uri = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
            .buildAndExpand(entity.getId()).toUri();
        return ResponseEntity.created(uri).body(new VolunteerDTO(entity));
    }
	
	@DeleteMapping(value = "/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id){
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
	
	@PutMapping(value = "/{id}")
	public ResponseEntity<VolunteerDTO> update(@PathVariable Long id, @Valid @RequestBody VolunteerUpdateDTO dto){
		Volunteer obj = service.update(id, dto.toEntity());
		return ResponseEntity.ok().body(new VolunteerDTO(obj));
	}
}
