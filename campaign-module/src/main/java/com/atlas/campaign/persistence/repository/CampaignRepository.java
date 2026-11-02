package com.atlas.campaign.persistence.repository;

import com.atlas.campaign.persistence.entity.Campaign;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {
}
