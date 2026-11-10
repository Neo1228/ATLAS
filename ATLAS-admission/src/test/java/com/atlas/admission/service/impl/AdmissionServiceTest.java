package com.atlas.admission.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.persistence.entity.AdmissionPolicy;
import com.atlas.admission.persistence.entity.QueueTicket;
import com.atlas.admission.persistence.state.CheckoutSlotReleaseReason;
import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.repository.AdmissionPolicyRepository;
import com.atlas.admission.repository.JdbcQueueRepository;
import com.atlas.admission.repository.TicketRepository;
import com.atlas.campaign.exception.CampaignException;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.persistence.state.CampaignStatus;
import com.atlas.campaign.service.CampaignService;
import com.atlas.catalog.dto.response.SellerSkuResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.service.ProductSalesChannel;
import com.atlas.catalog.service.SellerSkuService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
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
        when(sellerSkus.findOrderableSellerSku(sellerId, skuId, ProductSalesChannel.CAMPAIGN))
                .thenReturn(new SellerSkuResponse(sellerId, UUID.randomUUID(), skuId, "Product",
                        1000, null, null, null, 1000));
    }

    @Test
    void configureCreatesPolicyAfterVerifyingCatalogSku() {
        when(policies.findBySellerIdAndSkuId(sellerId, skuId)).thenReturn(Optional.empty());

        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 4));

        verify(sellerSkus).findOrderableSellerSku(sellerId, skuId, ProductSalesChannel.CAMPAIGN);
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
        when(sellerSkus.findOrderableSellerSku(sellerId, skuId, ProductSalesChannel.CAMPAIGN)).thenReturn(null);

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
    void unavailableProductRejectsJoinBeforeQueueOrIdempotencySideEffects() {
        UUID campaignId = UUID.randomUUID();
        when(campaigns.findOpenForAdmission(campaignId, skuId)).thenReturn(Optional.of(
                new CampaignResponse(campaignId, sellerId, skuId, CampaignStatus.OPEN, Duration.ofMinutes(5))));
        when(policies.findBySellerIdAndSkuId(sellerId, skuId))
                .thenReturn(Optional.of(new AdmissionPolicy(sellerId, skuId, 2)));
        when(sellerSkus.findOrderableSellerSku(sellerId, skuId, ProductSalesChannel.CAMPAIGN))
                .thenThrow(new CatalogException(CatalogException.Code.PRODUCT_NOT_ORDERABLE, "Unavailable"));

        assertThrows(CatalogException.class, () -> admission.join(new JoinQueueRequest(
                campaignId, skuId, "buyer", "idempotency")));

        verify(queueRepository, never()).createCounterIfAbsent(sellerId, skuId);
        verify(queueRepository, never()).incrementSequence(sellerId, skuId);
        verify(queueRepository, never()).lockCounter(sellerId, skuId);
        verify(tickets, never()).save(any());
    }

    @Test
    void closedCampaignRejectsNewJoinBeforeQueueMutation() {
        UUID campaignId = UUID.randomUUID();
        when(campaigns.findOpenForAdmission(campaignId, skuId)).thenReturn(Optional.empty());

        CampaignException exception = assertThrows(CampaignException.class,
                () -> admission.join(new JoinQueueRequest(campaignId, skuId, "buyer", "new-key")));

        assertEquals(CampaignException.Code.NOT_ELIGIBLE, exception.code());
        verify(queueRepository, never()).createCounterIfAbsent(sellerId, skuId);
        verify(queueRepository, never()).lockCounter(sellerId, skuId);
        verify(tickets, never()).save(any());
    }


    @Test
    void existingIdempotencyKeyReplaysAfterCampaignCloses() {
        UUID campaignId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        String key = "existing-key";
        Instant expiresAt = Instant.now().plusSeconds(60);
        QueueTicket prior = mock(QueueTicket.class);
        when(prior.getTicketId()).thenReturn(ticketId);
        when(prior.getCampaignId()).thenReturn(campaignId);
        when(prior.getSellerId()).thenReturn(sellerId);
        when(prior.getSkuId()).thenReturn(skuId);
        when(prior.getBuyerRef()).thenReturn("buyer");
        when(prior.getSequence()).thenReturn(1L);
        when(prior.getStatus()).thenReturn(TicketStatus.CHECKOUT_ACTIVE);
        when(prior.getExpiresAt()).thenReturn(expiresAt);
        when(prior.isCheckoutSlotHeld()).thenReturn(true);
        when(tickets.findByCampaignIdAndIdempotencyKey(campaignId, key))
                .thenReturn(Optional.of(prior));
        when(tickets.findByCampaignIdAndTicketId(campaignId, ticketId))
                .thenReturn(Optional.of(prior));
        when(tickets.existsByCampaignIdAndTicketIdAndSkuIdAndBuyerRef(campaignId, ticketId, skuId, "buyer"))
                .thenReturn(true);
        when(queueRepository.lockCounter(sellerId, skuId)).thenReturn(true);

        admission.join(new JoinQueueRequest(campaignId, skuId, "buyer", key));

        verify(sellerSkus, never()).findOrderableSellerSku(
                sellerId, skuId, ProductSalesChannel.CAMPAIGN);
        verify(queueRepository, never()).incrementSequence(sellerId, skuId);
        verify(tickets, never()).save(any());
        verify(campaigns, never()).findOpenForAdmission(campaignId, skuId);
        verify(policies, never()).findBySellerIdAndSkuId(sellerId, skuId);
    }

    @Test
    void productNotOrderableReleaseMarksFailureAndPromotesWaitingTicket() {
        UUID campaignId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        when(policies.findBySellerIdAndSkuId(sellerId, skuId))
                .thenReturn(Optional.of(new AdmissionPolicy(sellerId, skuId, 1)));
        when(tickets.findByCampaignIdAndTicketId(campaignId, ticketId)).thenReturn(Optional.of(
                new QueueTicket(campaignId, sellerId, skuId,
                        "buyer", "key", 1L, TicketStatus.CHECKOUT_ACTIVE, Instant.now().plusSeconds(60),
                        Instant.now(), Instant.now(), true)));
        when(queueRepository.lockCounter(sellerId, skuId)).thenReturn(true);
        UUID waitingId = UUID.randomUUID();
        when(tickets.findWaitingForSellerSku(eq(sellerId), eq(skuId), eq(TicketStatus.WAITING), any(Instant.class), any()))
                .thenReturn(List.of(waitingId));

        admission.releaseCheckoutSlot(campaignId, ticketId, CheckoutSlotReleaseReason.PRODUCT_NOT_ORDERABLE);

        verify(tickets).releaseSlot(campaignId, ticketId, TicketStatus.CHECKOUT_FAILED);
        verify(queueRepository).activateWaitingTicket(eq(waitingId), any(Instant.class));
    }

    @Test
    void rejectsNullSellerSkuPolicyRequest() {
        AdmissionException exception = assertThrows(AdmissionException.class,
                () -> admission.configureSellerSkuPolicy(null));
        assertEquals(AdmissionException.Code.INVALID_REQUEST, exception.code());
    }

}
