package com.raphael.beachcleanup.repositories;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.raphael.beachcleanup.entities.AuthSession;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> findLocked(@Param("id") String id);

    @Modifying
    @Query("update AuthSession s set s.revokedAt = :now where s.user.id = :userId and s.revokedAt is null")
    void revokeByUser(@Param("userId") Long userId, @Param("now") Instant now);
}
