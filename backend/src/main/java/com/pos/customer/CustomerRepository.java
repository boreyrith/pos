package com.pos.customer;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByDefaultCustomerTrue();

    Optional<Customer> findByPhone(String phone);

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
