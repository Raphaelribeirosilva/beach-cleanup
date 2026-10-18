package com.raphael.beachcleanup.security;

import java.time.Clock;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.raphael.beachcleanup.repositories.AuthSessionRepository;

@Component
public class SessionJwtValidator implements OAuth2TokenValidator<Jwt> {
    private final AuthSessionRepository sessions;
    private final Clock clock;
    private final com.raphael.beachcleanup.config.AccountPolicy policy;
    public SessionJwtValidator(AuthSessionRepository sessions, Clock clock, com.raphael.beachcleanup.config.AccountPolicy policy) {
        this.sessions = sessions; this.clock = clock; this.policy = policy;
    }

    @Override @Transactional(readOnly = true)
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object sid = jwt.getClaim("sid");
        if (sid instanceof String id && id.length() == 36) {
            var session = sessions.findById(id).orElse(null);
            if (session != null && session.active(clock.instant())
                    && (!policy.verifiedRequired || session.getUser().isEmailVerified())
                    && session.getUser().getId().toString().equals(jwt.getSubject()))
                return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
}
