package com.pos.payment;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByStatusAndExpiresAtBefore(PaymentStatus status, Instant before);
}
