package com.pos.catalog;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UnitRepository extends JpaRepository<Unit, Long> {

    Optional<Unit> findBySymbol(String symbol);

    /** How many catalog entries use the unit, as their base unit or as a pack. */
    @Query("""
            select (select count(o) from Offering o where o.unit.id = :unitId)
                 + (select count(p) from OfferingPack p where p.unit.id = :unitId)
            """)
    long usageCount(Long unitId);
}
