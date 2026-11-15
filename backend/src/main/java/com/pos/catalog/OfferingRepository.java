package com.pos.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.pos.common.OfferingType;

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
              and (:forSale = false or (o.active = true and o.tillHidden = false
                   and (o.type <> com.pos.common.OfferingType.ITEM or o.sellable = true)))
              and (:categoryId is null or o.category.id = :categoryId)
              and (:anyId = true or o.id in :ids)
              and (:lowStock = false or (o.type in (com.pos.common.OfferingType.PRODUCT, com.pos.common.OfferingType.ITEM)
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

    /**
     * The online shop's entries, by name: switched on for it, active, and nothing a customer can't buy
     * on their own (services, items not sold at the till). For one category and a search term if given.
     */
    @Query("""
            select o.id from Offering o
            where o.sellOnline = true and o.active = true
              and o.type <> com.pos.common.OfferingType.SERVICE
              and (o.type <> com.pos.common.OfferingType.ITEM or o.sellable = true)
              and (:categoryId is null or o.category.id = :categoryId)
              and (:q is null
                   or lower(o.name) like lower(concat('%', :q, '%'))
                   or o.nameKm like concat('%', :q, '%'))
            order by o.name, o.id
            """)
    Page<Long> findOnlineIds(String q, Long categoryId, Pageable pageable);

    /** The categories the online shop has something in, by name. */
    @Query("""
            select distinct c from Offering o join o.category c
            where o.sellOnline = true and o.active = true
              and o.type <> com.pos.common.OfferingType.SERVICE
              and (o.type <> com.pos.common.OfferingType.ITEM or o.sellable = true)
            order by c.name
            """)
    List<Category> findOnlineCategories();

    boolean existsBySku(String sku);

    long countByType(OfferingType type);

    Optional<Offering> findBySku(String sku);

    Optional<Offering> findFirstBySkuIgnoreCase(String sku);

}
