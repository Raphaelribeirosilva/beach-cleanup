package com.raphael.beachcleanup.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.entities.UserRole;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.UserRepository;

@Configuration
@Profile("test")
public class TestConfig implements CommandLineRunner {
	
	@Autowired
	CleanupEventRepository eventRepository;
	
	@Autowired
	TrashCollectionRepository trashRepository;
	
	@Autowired
	UserRepository userRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

    @Autowired
    com.raphael.beachcleanup.services.CatalogService catalogs;

    @Autowired
    com.raphael.beachcleanup.repositories.CatalogEntryRepository catalogEntries;
	
    @Override
    public void run(String... args) throws Exception {
        var beach = catalogs.create("beaches", new com.raphael.beachcleanup.dto.CatalogInputDTO("Praia do Morro", java.util.List.of("Morro"), null));
        var material = catalogs.create("materials", new com.raphael.beachcleanup.dto.CatalogInputDTO("Pl\u00e1stico", java.util.List.of(), null));
        for (String label : java.util.List.of("Vidro", "Metal", "Papel", "Res\u00edduos mistos")) {
            catalogs.create("materials", new com.raphael.beachcleanup.dto.CatalogInputDTO(label, java.util.List.of(), null));
        }
        User v1 = new User(null, "Raphael", "admin-original@example.com",
                passwordEncoder.encode("demo-original7!"), 15.5);
        v1.setRole(UserRole.ADMIN);

        User admin = new User(null, "Administrador de teste", "admin@example.com",
                passwordEncoder.encode("admin123"), 0.0);
        admin.setRole(UserRole.ADMIN);
        User ana = new User(null, "Ana Silva", "ana@example.com",
                passwordEncoder.encode("ana123"), 0.0);
        User bruno = new User(null, "Bruno Costa", "bruno@example.com",
                passwordEncoder.encode("bruno123"), 0.0);
        User carla = new User(null, "Carla Souza", "carla@example.com",
                passwordEncoder.encode("carla123"), 0.0);
        
        CleanupEvent e1 = new CleanupEvent(
	        		null,
	        		"Teste", 
	        		"Praia do Morro", 
	        		LocalDateTime.parse("2026-10-15T08:00:00"),
	        		"Pausado"	
        		);
        
        userRepository.saveAll(Arrays.asList(v1, admin, ana, bruno, carla));
        e1.setBeachReference(catalogEntries.findById(beach.id()).orElseThrow());
        eventRepository.saveAll(Arrays.asList(e1));
        
        v1.getEvents().add(e1);
        
        userRepository.saveAll(Arrays.asList(v1));
        
        TrashCollection t1 = new TrashCollection(null, "Praia do Morro", "Plástico", 15.5, e1.getDateTime().toLocalDate());
               
        t1.setBeachReference(catalogEntries.findById(beach.id()).orElseThrow());
        t1.setMaterialReference(catalogEntries.findById(material.id()).orElseThrow());
        t1.setVolunteer(v1);
        t1.setEvent(e1);
        
        trashRepository.save(t1);
    }
}
