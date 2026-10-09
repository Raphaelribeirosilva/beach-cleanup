package com.raphael.beachcleanup.resources;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.raphael.beachcleanup.dto.LoginDTO;
import com.raphael.beachcleanup.dto.TokenDTO;
import com.raphael.beachcleanup.services.AuthService;

@RestController
@RequestMapping("/auth")
public class AuthResource {
    private final AuthService service;

    public AuthResource(AuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenDTO> login(@Valid @RequestBody LoginDTO input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.login(input));
    }
}
