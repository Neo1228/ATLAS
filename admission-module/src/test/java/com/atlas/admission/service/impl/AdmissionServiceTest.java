package com.atlas.admission.service.impl;

import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.dto.request.JoinQueueRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.atlas.admission.persistence.repository.TicketRepository;
import com.atlas.admission.persistence.repository.JdbcQueueRepository;
import com.atlas.campaign.service.CampaignService;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdmissionServiceTest {

    @Test
    void rejectsIncompleteJoinBeforeReadingCampaignOrWritingTicket() {
        TicketRepository tickets = mock(TicketRepository.class);
        JdbcQueueRepository queueRepository = mock(JdbcQueueRepository.class);
        CampaignService campaigns = mock(CampaignService.class);
        AdmissionServiceImpl admission = new AdmissionServiceImpl(tickets, queueRepository, campaigns, Clock.systemUTC());

        assertThrows(AdmissionException.class, () -> admission.join(
                new JoinQueueRequest(UUID.randomUUID(), "sku-1", "buyer-1", " ")));

        verifyNoInteractions(tickets, queueRepository, campaigns);
    }
}
