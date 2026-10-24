package com.pos.catalog;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OfferingBarcodeRepository extends JpaRepository<OfferingBarcode, Long> {

    @EntityGraph(attributePaths = "offering")
    List<OfferingBarcode> findByCodeIn(Collection<String> codes);

    boolean existsByCode(String code);

    /** In-store codes already handed out with this prefix (all 13 digits), highest first. */
    @Query("select b.code from OfferingBarcode b where b.code like concat(:prefix, '%') and length(b.code) = 13 order by b.code desc")
    List<String> findInStoreCodes(String prefix, Pageable page);
}
