package com.raphael.beachcleanup.resources;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.raphael.beachcleanup.dto.LoginDTO;
import com.raphael.beachcleanup.dto.TokenDTO;
import com.raphael.beachcleanup.services.AuthService;
import com.raphael.beachcleanup.dto.ChangePasswordDTO;
import com.raphael.beachcleanup.dto.RefreshTokenDTO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

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

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt principal,
            @Valid @RequestBody ChangePasswordDTO input) {
        service.changePassword(principal, input);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/refresh", consumes = "application/json")
    public ResponseEntity<TokenDTO> refresh(@Valid @RequestBody RefreshTokenDTO input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.refresh(input.refreshToken()));
    }

    @PostMapping(value = "/logout", consumes = "application/json")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenDTO input) {
        service.logout(input.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
