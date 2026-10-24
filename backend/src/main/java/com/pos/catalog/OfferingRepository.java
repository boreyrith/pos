package com.pos.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OfferingRepository extends JpaRepository<Offering, Long> {

    /**
     * Catalog search. {@code types} limits the result to some offering types; {@code forSale} keeps
     * only what can go in a cart (active, and items only when sellable).
     */
    @EntityGraph(attributePaths = {"category", "unit", "taxRate"})
    @Query("""
            select o from Offering o
            where o.type in :types
              and (:activeOnly = false or o.active = true)
              and (:forSale = false or (o.active = true and (o.type <> com.pos.catalog.OfferingType.ITEM or o.sellable = true)))
              and (:categoryId is null or o.category.id = :categoryId)
              and (:anyId = true or o.id in :ids)
              and (:lowStock = false or (o.type in (com.pos.catalog.OfferingType.PRODUCT, com.pos.catalog.OfferingType.ITEM)
                   and (select coalesce(sum(l.quantity), 0) from StockLevel l
                        where l.offering = o and l.storage.id in :storageIds) <= o.lowStockThreshold))
              and (:q is null
                   or lower(o.name) like lower(concat('%', :q, '%'))
                   or o.nameKm like concat('%', :q, '%')
                   or lower(o.sku) like lower(concat('%', :q, '%'))
                   or exists (select 1 from OfferingBarcode b where b.offering = o and b.code = :q))
            """)
    Page<Offering> search(String q, Collection<OfferingType> types, Long categoryId, boolean activeOnly,
                          boolean forSale, boolean lowStock, Collection<Long> storageIds, boolean anyId, Collection<Long> ids,
                          Pageable pageable);

    /** For scanning: a barcode match, or a SKU match (QR labels often carry the SKU). */
    @EntityGraph(attributePaths = {"category", "unit", "taxRate"})
    @Query("""
            select o from Offering o
            where upper(o.sku) = upper(:code)
               or exists (select 1 from OfferingBarcode b where b.offering = o and b.code = :code)
            """)
    List<Offering> findByScannedCode(String code);

    /** Packs are loaded lazily inside the transaction; two bag collections can't be fetched at once. */
    @EntityGraph(attributePaths = {"category", "unit", "taxRate", "components", "components.item"})
    Optional<Offering> findWithComponentsById(Long id);

    @EntityGraph(attributePaths = {"unit", "taxRate", "components", "components.item"})
    List<Offering> findWithComponentsByIdIn(Collection<Long> ids);

    boolean existsBySku(String sku);

    long countByType(OfferingType type);

    Optional<Offering> findBySku(String sku);

    Optional<Offering> findFirstBySkuIgnoreCase(String sku);

}
