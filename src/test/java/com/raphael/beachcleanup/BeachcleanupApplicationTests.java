package com.raphael.beachcleanup;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.raphael.beachcleanup.repositories.VolunteerRepository;
import com.raphael.beachcleanup.entities.VolunteerRole;
import java.util.Map;

@SpringBootTest
class BeachcleanupApplicationTests {

	@Autowired
	private VolunteerRepository volunteers;
	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void contextLoads() {
		var seeded = volunteers.findAll();
        Map<String, String> credentials = Map.of(
            "admin-original@example.com", "demo-original7!",
            "admin@example.com", "admin123",
            "ana@example.com", "ana123",
            "bruno@example.com", "bruno123",
            "carla@example.com", "carla123");
        assertEquals(credentials.size(), seeded.size());
		for (var volunteer : seeded) {
            String password = credentials.get(volunteer.getEmail());
            assertNotNull(password);
            assertNotEquals(password, volunteer.getPassword());
            assertTrue(passwordEncoder.matches(password, volunteer.getPassword()));
            boolean admin = volunteer.getEmail().equals("admin-original@example.com")
                || volunteer.getEmail().equals("admin@example.com");
            assertEquals(admin ? VolunteerRole.ADMIN : VolunteerRole.VOLUNTEER, volunteer.getRole());
            assertEquals(0, volunteer.getCredentialVersion());
		}
	}

}
