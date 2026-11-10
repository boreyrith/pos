package com.pos.marketing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface CouponCodeRepository extends JpaRepository<CouponCode, Long> {

    @Query("select c from CouponCode c join fetch c.promotion left join fetch c.customer where upper(c.code) = upper(:code)")
    Optional<CouponCode> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CouponCode c where c.id = :id")
    Optional<CouponCode> lockById(Long id);

    @Query("select c from CouponCode c left join fetch c.customer where c.promotion.id = :promotionId order by c.id")
    List<CouponCode> findByPromotion(Long promotionId);

    boolean existsByCodeIgnoreCase(String code);
}
