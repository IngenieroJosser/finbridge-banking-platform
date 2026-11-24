package com.finbridge.paymenthub.account.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface AccountBalanceRepository extends JpaRepository<AccountBalanceEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AccountBalanceEntity b where b.accountId = :id")
    Optional<AccountBalanceEntity> lockById(@Param("id") UUID id);
}
