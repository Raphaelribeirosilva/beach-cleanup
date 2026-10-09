package com.raphael.beachcleanup;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.raphael.beachcleanup.repositories.VolunteerRepository;

@SpringBootTest
class BeachcleanupApplicationTests {

	@Autowired
	private VolunteerRepository volunteers;
	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void contextLoads() {
		var seeded = volunteers.findAll();
		assertFalse(seeded.isEmpty());
		for (var volunteer : seeded) {
			assertNotEquals("demo-original7!", volunteer.getPassword());
			assertTrue(passwordEncoder.matches("demo-original7!", volunteer.getPassword()));
		}
	}

}
