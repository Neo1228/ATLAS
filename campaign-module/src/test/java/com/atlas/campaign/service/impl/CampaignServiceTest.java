package com.atlas.campaign.service.impl;

import com.atlas.campaign.exception.CampaignException;
import com.atlas.campaign.dto.request.CreateCampaignRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.atlas.campaign.persistence.repository.CampaignRepository;
import com.atlas.inventory.service.InventoryService;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CampaignServiceTest {

    @Test
    void rejectsInvalidConfigurationBeforePersistingOrCreatingInventory() {
        CampaignRepository campaignsRepository = mock(CampaignRepository.class);
        InventoryService inventory = mock(InventoryService.class);
        CampaignServiceImpl campaigns = new CampaignServiceImpl(campaignsRepository, inventory, Clock.systemUTC());

        assertThrows(CampaignException.class, () -> campaigns.create(
                new CreateCampaignRequest("sku-1", 0, Duration.ofMinutes(5), 1)));

        verifyNoInteractions(campaignsRepository, inventory);
    }
}
