package com.raphael.beachcleanup.repositories;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.raphael.beachcleanup.entities.AuthRateLimit;
public interface AuthRateLimitRepository extends JpaRepository<AuthRateLimit, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AuthRateLimit b where b.bucketKey = :key")
    Optional<AuthRateLimit> locked(@Param("key") String key);
}
