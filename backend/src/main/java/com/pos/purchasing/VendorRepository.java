package com.pos.purchasing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorRepository extends JpaRepository<Vendor, Long> {

    boolean existsByCodeIgnoreCase(String code);

    List<Vendor> findAllByOrderByNameAsc();
}
