package com.pos.marketing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    @Query("select c from Campaign c left join fetch c.promotion order by c.createdAt desc")
    List<Campaign> findAllNewestFirst();
}
