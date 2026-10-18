package com.raphael.beachcleanup.repositories;

import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.raphael.beachcleanup.entities.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    interface Owner { Long getUserId(); String getSessionId(); }
    @Query("select t.session.user.id as userId, t.session.id as sessionId from RefreshToken t where t.tokenHash = :hash")
    Optional<Owner> owner(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findLocked(@Param("hash") String hash);
}
