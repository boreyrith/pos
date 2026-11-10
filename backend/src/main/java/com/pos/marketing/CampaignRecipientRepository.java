package com.pos.marketing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, Long> {

    @Query("select r from CampaignRecipient r left join fetch r.couponCode where r.campaign.id = :campaignId order by r.id")
    List<CampaignRecipient> findByCampaign(Long campaignId);
}
