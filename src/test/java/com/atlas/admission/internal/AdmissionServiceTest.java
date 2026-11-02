package com.atlas.admission.internal;

import com.atlas.admission.JoinQueueCommand;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.atlas.campaign.CampaignApi;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AdmissionServiceTest {

    @Test
    void rejectsIncompleteJoinBeforeReadingCampaignOrWritingTicket() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CampaignApi campaigns = mock(CampaignApi.class);
        AdmissionService admission = new AdmissionService(jdbc, campaigns, Clock.systemUTC());

        assertThrows(IllegalArgumentException.class, () -> admission.join(
                new JoinQueueCommand(UUID.randomUUID(), "sku-1", "buyer-1", " ")));

        verifyNoInteractions(jdbc, campaigns);
    }
}
