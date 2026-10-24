package com.pos.tax;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {

    List<TaxRate> findAllByOrderBySortOrderAscNameAsc();

    Optional<TaxRate> findByDefaultRateTrue();

    Optional<TaxRate> findByName(String name);

    boolean existsByName(String name);

    @Query("select count(o) from Offering o where o.taxRate.id = :id")
    long usageCount(Long id);
}
