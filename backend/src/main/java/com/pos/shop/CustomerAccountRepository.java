package com.pos.shop;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface CustomerAccountRepository extends JpaRepository<CustomerAccount, Long> {

    @EntityGraph(attributePaths = "customer")
    Optional<CustomerAccount> findByPhone(String phone);

    boolean existsByPhone(String phone);

    boolean existsByCustomerId(Long customerId);

    @EntityGraph(attributePaths = "customer")
    Optional<CustomerAccount> findByCustomerId(Long customerId);

    /** Failed sign-ins are counted under this lock, so two at once can't both escape the limit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CustomerAccount a where a.id = :id")
    Optional<CustomerAccount> lockById(Long id);
}
