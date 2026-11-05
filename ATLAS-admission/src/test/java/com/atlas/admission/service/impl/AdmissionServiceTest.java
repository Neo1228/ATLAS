package com.atlas.admission.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.persistence.entity.AdmissionPolicy;
import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.repository.AdmissionPolicyRepository;
import com.atlas.admission.repository.JdbcQueueRepository;
import com.atlas.admission.repository.TicketRepository;
import com.atlas.campaign.service.CampaignService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.service.SellerSkuService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class AdmissionServiceTest {
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final JdbcQueueRepository queueRepository = mock(JdbcQueueRepository.class);
    private final CampaignService campaigns = mock(CampaignService.class);
    private final SellerSkuService sellerSkus = mock(SellerSkuService.class);
    private final AdmissionPolicyRepository policies = mock(AdmissionPolicyRepository.class);
    private final AdmissionServiceImpl admission = new AdmissionServiceImpl(
            tickets, queueRepository, campaigns, policies, sellerSkus, Clock.systemUTC());
    private final UUID sellerId = UUID.randomUUID();
    private final String skuId = "sku-1";

    @BeforeEach
    void sellerSkuExists() {
        when(sellerSkus.find(sellerId, skuId))
                .thenReturn(new SellerSkuResponse(sellerId, UUID.randomUUID(), skuId, "Product", 1000));
    }

    @Test
    void configureCreatesPolicyAfterVerifyingCatalogSku() {
        when(policies.findBySellerIdAndSkuId(sellerId, skuId)).thenReturn(Optional.empty());

        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 4));

        verify(sellerSkus).find(sellerId, skuId);
        verify(policies).save(argThat(policy ->
                policy.getSellerId().equals(sellerId) && policy.getSkuId().equals(skuId)
                        && policy.getMaxActiveCheckouts() == 4));
    }

    @Test
    void configureUpdatesExistingPolicy() {
        AdmissionPolicy existing = new AdmissionPolicy(sellerId, skuId, 2);
        when(policies.findBySellerIdAndSkuId(sellerId, skuId)).thenReturn(Optional.of(existing));

        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 5));

        assertEquals(5, existing.getMaxActiveCheckouts());
        verify(policies).save(existing);
    }

    @Test
    void configureRejectsUnknownSku() {
        when(sellerSkus.find(sellerId, skuId)).thenReturn(null);

        assertThrows(AdmissionException.class, () -> admission.configureSellerSkuPolicy(
                new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 3)));
    }

    @Test
    void missingPolicyIsRejectedInsteadOfReadingCapacityFromCatalog() throws Exception {
        when(policies.findBySellerIdAndSkuId(sellerId, skuId)).thenReturn(Optional.empty());
        Method requirePolicy = AdmissionServiceImpl.class.getDeclaredMethod("requirePolicy", UUID.class, String.class);
        requirePolicy.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> requirePolicy.invoke(admission, sellerId, skuId));

        assertEquals(AdmissionException.class, thrown.getCause().getClass());
    }

    @Test
    void promotionUsesPolicyCapacityAndKeepsWaitingSelectionFifoPaged() throws Exception {
        UUID firstWaitingTicket = UUID.randomUUID();
        when(policies.findBySellerIdAndSkuId(sellerId, skuId))
                .thenReturn(Optional.of(new AdmissionPolicy(sellerId, skuId, 3)));
        when(tickets.countBySellerIdAndSkuIdAndCheckoutSlotHeldTrue(sellerId, skuId)).thenReturn(1L);
        when(tickets.findWaitingForSellerSku(eq(sellerId), eq(skuId), eq(TicketStatus.WAITING), any(Instant.class), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(firstWaitingTicket));
        Method promote = AdmissionServiceImpl.class.getDeclaredMethod("promote", UUID.class, String.class);
        promote.setAccessible(true);

        promote.invoke(admission, sellerId, skuId);

        verify(tickets).findWaitingForSellerSku(eq(sellerId), eq(skuId), eq(TicketStatus.WAITING), any(Instant.class), eq(PageRequest.of(0, 2)));
        verify(queueRepository).activateWaitingTicket(eq(firstWaitingTicket), any(Instant.class));
    }

    @Test
    void rejectsNullSellerSkuPolicyRequest() {
        AdmissionException exception = assertThrows(AdmissionException.class,
                () -> admission.configureSellerSkuPolicy(null));
        assertEquals(AdmissionException.Code.INVALID_REQUEST, exception.code());
    }

}
