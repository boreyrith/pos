package com.pos.currency;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    @EntityGraph(attributePaths = "createdBy")
    Optional<ExchangeRate> findFirstByCurrencyCodeOrderByCreatedAtDescIdDesc(String currencyCode);

    @EntityGraph(attributePaths = "createdBy")
    List<ExchangeRate> findByCurrencyCodeOrderByCreatedAtDescIdDesc(String currencyCode, Pageable pageable);
}
