package com.atlas.campaign.repository;

import com.atlas.campaign.persistence.entity.Campaign;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select campaign from Campaign campaign where campaign.campaignId = :campaignId")
    Optional<Campaign> findForAdmission(@Param("campaignId") UUID campaignId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select campaign from Campaign campaign where campaign.campaignId = :campaignId")
    Optional<Campaign> findWithWriteLockByCampaignId(@Param("campaignId") UUID campaignId);
}
