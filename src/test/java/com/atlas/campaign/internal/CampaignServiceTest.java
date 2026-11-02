package com.atlas.campaign.internal;

import com.atlas.campaign.CreateCampaignCommand;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.atlas.inventory.InventoryApi;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CampaignServiceTest {

    @Test
    void rejectsInvalidConfigurationBeforePersistingOrCreatingInventory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InventoryApi inventory = mock(InventoryApi.class);
        CampaignService campaigns = new CampaignService(jdbc, inventory, Clock.systemUTC());

        assertThrows(IllegalArgumentException.class, () -> campaigns.create(
                new CreateCampaignCommand("sku-1", 0, Duration.ofMinutes(5), 1)));

        verifyNoInteractions(jdbc, inventory);
    }
}
