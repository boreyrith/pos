package com.pos.purchasing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface VendorInvoiceRepository extends JpaRepository<VendorInvoice, Long> {

    boolean existsByVendorIdAndInvoiceNumberIgnoreCase(Long vendorId, String invoiceNumber);

    List<VendorInvoice> findByPurchaseOrderIdOrderByIdAsc(Long purchaseOrderId);

    @Query("""
            select i from VendorInvoice i join fetch i.vendor
            where (:vendorId is null or i.vendor.id = :vendorId)
              and (:status is null or i.status = :status)
              and (:overdue = false or (i.status in ('UNPAID', 'PARTIALLY_PAID') and i.dueDate < CURRENT_DATE))
              and (:q is null or i.invoiceNumber like concat('%', :q, '%') or i.vendor.name like concat('%', :q, '%'))
            order by i.dueDate asc, i.id desc
            """)
    Page<VendorInvoice> search(Long vendorId, InvoiceStatus status, boolean overdue, String q, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from VendorInvoice i where i.id = :id")
    Optional<VendorInvoice> lockById(Long id);

    /** Owed per vendor as [vendorId, balance], for invoices not paid or voided. */
    @Query("select i.vendor.id, sum(i.total - i.paid) from VendorInvoice i where i.status in ('UNPAID', 'PARTIALLY_PAID') group by i.vendor.id")
    List<Object[]> balances();

    @Query("select coalesce(sum(i.total - i.paid), 0) from VendorInvoice i where i.status in ('UNPAID', 'PARTIALLY_PAID') and i.dueDate < CURRENT_DATE")
    BigDecimal overdueTotal();
}
