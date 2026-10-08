package com.raphael.beachcleanup.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.entities.Volunteer;
import com.raphael.beachcleanup.repositories.CleanupEventRepository;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;
import com.raphael.beachcleanup.repositories.VolunteerRepository;

@Configuration
@Profile("test")
public class TestConfig implements CommandLineRunner {
	
	@Autowired
	CleanupEventRepository eventRepository;
	
	@Autowired
	TrashCollectionRepository trashRepository;
	
	@Autowired
	VolunteerRepository volunteerRepository;
	
    @Override
    public void run(String... args) throws Exception {
        Volunteer v1 = new Volunteer(null, "Raphael", "admin-original@example.com", "demo-original7!", 20.0);
        
        CleanupEvent e1 = new CleanupEvent(
	        		null,
	        		"Teste", 
	        		"Praia do Morro", 
	        		LocalDateTime.parse("2026-10-15T08:00:00"),
	        		"Pausado"	
        		);
        
        volunteerRepository.saveAll(Arrays.asList(v1));
        eventRepository.saveAll(Arrays.asList(e1));
        
        v1.getEvents().add(e1);
        
        volunteerRepository.saveAll(Arrays.asList(v1));
        
        TrashCollection t1 = new TrashCollection(null, "Praia do Morro", "Plástico", 15.5, LocalDate.now());
               
        t1.setVolunteer(v1);
        t1.setEvent(e1);
        
        trashRepository.save(t1);
    }
}