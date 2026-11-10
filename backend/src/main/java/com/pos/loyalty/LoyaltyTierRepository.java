package com.pos.loyalty;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.customer.Tier;

public interface LoyaltyTierRepository extends JpaRepository<LoyaltyTier, Tier> {

    List<LoyaltyTier> findAllByOrderByRankOrderAsc();
}
