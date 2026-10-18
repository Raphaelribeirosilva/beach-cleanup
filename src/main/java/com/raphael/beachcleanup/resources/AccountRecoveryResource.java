package com.raphael.beachcleanup.resources;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.raphael.beachcleanup.services.AccountActionService;

@RestController @RequestMapping("/auth")
public class AccountRecoveryResource {
    public record EmailInput(@NotBlank @Email @Size(max = 255) String email) {}
    public record TokenInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String token) {
        @Override public String toString() { return "TokenInput[REDACTED]"; }
    }
    public record ResetInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String token,
            @NotBlank @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String newPassword) {
        @Override public String toString() { return "ResetInput[REDACTED]"; }
    }
    public record Notice(String message) {}
    public static final String GENERIC = "Se o endereço estiver apto, você receberá uma mensagem com as instruções. Verifique também a pasta de spam.";
    private final AccountActionService service;
    public AccountRecoveryResource(AccountActionService service) { this.service = service; }
    @PostMapping(value = "/recovery", consumes = "application/json")
    public ResponseEntity<Notice> recovery(@Valid @RequestBody EmailInput input) { service.request(input.email(), AccountActionService.RESET); return accepted(); }
    @PostMapping(value = "/verification", consumes = "application/json")
    public ResponseEntity<Notice> verification(@Valid @RequestBody EmailInput input) { service.request(input.email(), AccountActionService.VERIFY); return accepted(); }
    @PostMapping(value = "/reset-password", consumes = "application/json")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetInput input) { service.reset(input.token(), input.newPassword()); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
    @PostMapping(value = "/verify-email", consumes = "application/json")
    public ResponseEntity<Void> verify(@Valid @RequestBody TokenInput input) { service.verify(input.token()); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
    private ResponseEntity<Notice> accepted() { return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new Notice(GENERIC)); }
}
