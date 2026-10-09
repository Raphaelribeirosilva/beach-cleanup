package com.raphael.beachcleanup;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.raphael.beachcleanup.repositories.UserRepository;
import com.raphael.beachcleanup.entities.UserRole;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class BeachcleanupApplicationTests {

	@Autowired
	private UserRepository users;
	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	@Transactional
	void contextLoads() {
		var seeded = users.findAll();
        Map<String, String> credentials = Map.of(
            "admin-original@example.com", "demo-original7!",
            "admin@example.com", "admin123",
            "ana@example.com", "ana123",
            "bruno@example.com", "bruno123",
            "carla@example.com", "carla123");
        assertEquals(credentials.size(), seeded.size());
		for (var account : seeded) {
            String password = credentials.get(account.getEmail());
            assertNotNull(password);
            assertNotEquals(password, account.getPassword());
            assertTrue(passwordEncoder.matches(password, account.getPassword()));
            boolean admin = account.getEmail().equals("admin-original@example.com")
                || account.getEmail().equals("admin@example.com");
            assertEquals(admin ? UserRole.ADMIN : UserRole.VOLUNTEER, account.getRole());
            assertEquals(0, account.getCredentialVersion());

            if (account.getEmail().equals("admin-original@example.com")) {
                assertEquals(20.0, account.getTotalCollectedKg());
                assertEquals(1, account.getEvents().size());
                assertTrue(account.getEvents().iterator().next().getUsers().contains(account));
                assertEquals(1, account.getCollection().size());
                assertEquals(15.5, account.getCollection().getFirst().getWeightKg());
                assertEquals(account, account.getCollection().getFirst().getVolunteer());
            } else {
                assertEquals(0.0, account.getTotalCollectedKg());
                assertTrue(account.getEvents().isEmpty());
                assertTrue(account.getCollection().isEmpty());
            }
		}
	}

}
