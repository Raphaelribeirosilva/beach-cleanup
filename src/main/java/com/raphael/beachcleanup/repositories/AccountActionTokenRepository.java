package com.raphael.beachcleanup.repositories;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.raphael.beachcleanup.entities.AccountActionToken;

public interface AccountActionTokenRepository extends JpaRepository<AccountActionToken, String> {
    @Query("select t.user.id from AccountActionToken t where t.tokenHash = :hash")
    Optional<Long> owner(@Param("hash") String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AccountActionToken t where t.tokenHash = :hash")
    Optional<AccountActionToken> findLocked(@Param("hash") String hash);
}
