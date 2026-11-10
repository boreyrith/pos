package com.pos.customer;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByDefaultCustomerTrue();

    Optional<Customer> findByPhone(String phone);

    /** Locked while points change, so two sales can't spend the same points. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> lockById(Long id);

    /** Default customer first, then by name. {@code phone} is the normalised digits of q, for phone lookups. */
    @Query("""
            select c from Customer c
            where (:activeOnly = false or c.active = true)
              and (:q is null or c.name like concat('%', :q, '%') or c.code like concat('%', :q, '%')
                   or c.email like concat('%', :q, '%') or (:phone is not null and c.phone like concat('%', :phone, '%')))
            order by c.defaultCustomer desc, c.name asc
            """)
    Page<Customer> search(String q, String phone, boolean activeOnly, Pageable pageable);
}
