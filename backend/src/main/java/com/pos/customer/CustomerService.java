package com.pos.customer;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Customers, including the built-in Walk-in Customer that sales use by default. */
@Service
public class CustomerService {

    public record CustomerRequest(@NotBlank @Size(max = 150) String name, @Size(max = 30) String phone,
                                  @Email @Size(max = 100) String email, @Size(max = 255) String address,
                                  @Size(max = 50) String taxId, @Size(max = 500) String note, Boolean active) {
    }

    /** {@code walkIn} marks the default customer. */
    public record CustomerDto(Long id, String code, String name, String phone, String email, String address, String taxId,
                              String note, boolean active, boolean walkIn, Instant createdAt) {
        public static CustomerDto from(Customer c) {
            return new CustomerDto(c.getId(), c.getCode(), c.getName(), c.getPhone(), c.getEmail(), c.getAddress(), c.getTaxId(),
                    c.getNote(), c.isActive(), c.isDefaultCustomer(), c.getCreatedAt());
        }
    }

    /** What a customer has bought: completed (incl. partly refunded) orders, net of refunds. */
    public record CustomerStats(long orders, BigDecimal spent, BigDecimal refunded, Instant firstVisit, Instant lastVisit) {
    }

    public record CustomerDetail(CustomerDto customer, CustomerStats stats) {
    }

    private final CustomerRepository customers;
    private final UserRepository users;
    private final AuditService audit;
    private final JdbcClient jdbc;

    public CustomerService(CustomerRepository customers, UserRepository users, AuditService audit, JdbcClient jdbc) {
        this.customers = customers;
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page<CustomerDto> search(String q, boolean activeOnly, Pageable pageable) {
        String term = blank(q);
        String digits = term == null ? null : normalizePhone(term);
        return customers.search(term, digits == null || digits.replace("+", "").length() < 3 ? null : digits, activeOnly, pageable)
                .map(CustomerDto::from);
    }

    @Transactional(readOnly = true)
    public CustomerDetail get(Long id) {
        Customer c = find(id);
        return new CustomerDetail(CustomerDto.from(c), stats(id));
    }

    /** Branch-wide totals for a customer; {@code branchId} null means every branch. */
    @Transactional(readOnly = true)
    public CustomerStats stats(Long customerId) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS orders, COALESCE(SUM(total), 0) AS gross, COALESCE(SUM(refunded_total), 0) AS refunded,
                               MIN(completed_at) AS first_visit, MAX(completed_at) AS last_visit
                        FROM orders WHERE customer_id = :id AND status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                        """)
                .param("id", customerId)
                .query((rs, n) -> new CustomerStats(rs.getLong("orders"), rs.getBigDecimal("gross").subtract(rs.getBigDecimal("refunded")),
                        rs.getBigDecimal("refunded"),
                        rs.getTimestamp("first_visit") == null ? null : rs.getTimestamp("first_visit").toInstant(),
                        rs.getTimestamp("last_visit") == null ? null : rs.getTimestamp("last_visit").toInstant()))
                .single();
    }

    @Transactional(readOnly = true)
    public Customer walkIn() {
        return customers.findByDefaultCustomerTrue().orElseThrow(() -> new IllegalStateException("No default customer"));
    }

    /** The customer for a sale: the one asked for (must be active), or the Walk-in Customer. */
    @Transactional(readOnly = true)
    public Customer forSale(Long customerId) {
        if (customerId == null) {
            return walkIn();
        }
        Customer c = customers.findById(customerId).orElseThrow(() -> ApiException.badRequest("That customer doesn't exist"));
        if (!c.isActive()) {
            throw ApiException.badRequest(c.getName() + " is no longer an active customer");
        }
        return c;
    }

    @Transactional
    public CustomerDto create(CustomerRequest req, AuthUser user) {
        Customer c = new Customer();
        apply(c, req);
        c.setCreatedBy(users.getReferenceById(user.id()));
        customers.saveAndFlush(c);
        c.setCode(String.format("CUS-%05d", c.getId()));
        audit.log(AuditAction.CUSTOMER_CREATED, "Customer", c.getId(), "Added customer " + c.getName() + " (" + c.getCode()
                + (c.getPhone() == null ? "" : ", " + c.getPhone()) + ")");
        return CustomerDto.from(c);
    }

    @Transactional
    public CustomerDto update(Long id, CustomerRequest req) {
        Customer c = find(id);
        if (c.isDefaultCustomer() && req.active() != null && !req.active()) {
            throw ApiException.badRequest("The Walk-in Customer is used for every sale without a customer and can't be deactivated");
        }
        Changes changes = new Changes()
                .add("Name", c.getName(), req.name().trim())
                .add("Phone", c.getPhone(), normalizePhone(req.phone()))
                .add("Email", c.getEmail(), blank(req.email()))
                .add("Address", c.getAddress(), blank(req.address()))
                .add("Tax ID", c.getTaxId(), blank(req.taxId()))
                .add("Note", c.getNote(), blank(req.note()))
                .add("Active", c.isActive() ? "yes" : "no", req.active() == null || req.active() ? "yes" : "no");
        apply(c, req);
        customers.flush();
        if (!changes.isEmpty()) {
            audit.log(AuditAction.CUSTOMER_UPDATED, "Customer", c.getId(), "Updated customer " + c.getName() + ": " + changes.describe(), changes);
        }
        return CustomerDto.from(c);
    }

    private void apply(Customer c, CustomerRequest req) {
        String phone = normalizePhone(req.phone());
        if (phone != null) {
            customers.findByPhone(phone).filter(other -> !other.getId().equals(c.getId())).ifPresent(other -> {
                throw new ApiException(HttpStatus.CONFLICT, "Phone " + phone + " already belongs to " + other.getName() + " (" + other.getCode() + ")");
            });
        }
        c.setName(req.name().trim());
        c.setPhone(phone);
        c.setEmail(blank(req.email()));
        c.setAddress(blank(req.address()));
        c.setTaxId(blank(req.taxId()));
        c.setNote(blank(req.note()));
        c.setActive(c.isDefaultCustomer() || req.active() == null || req.active());
    }

    private Customer find(Long id) {
        return customers.findById(id).orElseThrow(() -> ApiException.notFound("Customer"));
    }

    /** "012 345 678" and "012-345-678" are the same number: keep digits and a leading +. */
    static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        String digits = t.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        return (t.startsWith("+") ? "+" : "") + digits;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
