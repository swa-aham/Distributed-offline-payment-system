package com.offlineupi.backend.model;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByVpa(String vpa);

    /**
     * Pessimistic write lock — used during settlement to prevent
     * concurrent debits from reading a stale balance.
     * Combined with @Version optimistic lock = two layers of protection.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.vpa = :vpa")
    Optional<Account> findByVpaForUpdate(String vpa);
}
