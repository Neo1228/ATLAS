package com.atlas.admission.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.persistence.entity.AdmissionPolicy;
import com.atlas.admission.persistence.entity.QueueTicket;
import com.atlas.admission.persistence.projection.DueTicketCandidate;
import com.atlas.admission.persistence.projection.DueTicketCursor;
import com.atlas.admission.model.CheckoutSlotReleaseReason;
import com.atlas.admission.model.TicketStatus;
import com.atlas.admission.repository.AdmissionPolicyRepository;
import com.atlas.admission.repository.QueueRepository;
import com.atlas.admission.repository.TicketRepository;
import com.atlas.admission.exception.CampaignException;
import com.atlas.admission.model.CampaignStatus;
import com.atlas.admission.service.AdmissionLimits;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.model.ProductSalesChannel;
import com.atlas.catalog.service.SellerSkuService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;

class AdmissionServiceTest {
    private static final AdmissionLimits LIMITS =
            new AdmissionLimits(20, 100, Duration.ofMinutes(7), Duration.ofHours(2), 1);

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final QueueRepository queueRepository = mock(QueueRepository.class);
    private final CampaignService campaigns = mock(CampaignService.class);
    private final SellerSkuService sellerSkus = mock(SellerSkuService.class);
    private final AdmissionPolicyRepository policies = mock(AdmissionPolicyRepository.class);
    private final AdmissionServiceImpl admission = new AdmissionServiceImpl(
            tickets, queueRepository, campaigns, policies, sellerSkus, Clock.systemUTC(),
            mock(PlatformTransactionManager.class), LIMITS, 100, Duration.ofSeconds(2));
    private final UUID sellerId = UUID.randomUUID();
    private final String skuId = "sku-1";


    @Test
    void missingCounterDoesNotAbortRemainingExpiryCandidates() {
        UUID missingSeller = UUID.randomUUID();
        UUID activeSeller = UUID.randomUUID();
        String missingSku = "missing-counter";
        String activeSku = "active-counter";
        Instant expiresAt = Instant.now().minusSeconds(1);
        when(queueRepository.findDueTicketCandidates(any(Instant.class), isNull(), anyInt(), any(Duration.class)))
                .thenReturn(List.of(
                        new DueTicketCandidate(
                                UUID.randomUUID(), missingSeller, missingSku, expiresAt),
                        new DueTicketCandidate(
                                UUID.randomUUID(), activeSeller, activeSku, expiresAt)));
        when(queueRepository.tryLockCounter(missingSeller, missingSku))
                .thenReturn(QueueRepository.CounterLockResult.MISSING);
        when(queueRepository.tryLockCounter(activeSeller, activeSku))
                .thenReturn(QueueRepository.CounterLockResult.ACQUIRED);
        when(queueRepository.expireDueTicketCandidates(
                eq(activeSeller), eq(activeSku), any(), any(Instant.class)))
                .thenReturn(0);

        assertEquals(0, admission.expireDueTickets());

        verify(queueRepository).tryLockCounter(missingSeller, missingSku);
        verify(queueRepository).tryLockCounter(activeSeller, activeSku);
        verify(queueRepository).expireDueTicketCandidates(
                eq(activeSeller), eq(activeSku), any(), any(Instant.class));
    }
    @BeforeEach
    void sellerSkuExists() {
        when(sellerSkus.findOrderableSellerSku(sellerId, skuId, ProductSalesChannel.CAMPAIGN))
                .thenReturn(new CatalogResponse.SellerSku(sellerId, UUID.randomUUID(), skuId, "Product",
                        1000, null, null, null, 1000));
    }

    @Test
    void configureCreatesPolicyAfterVerifyingCatalogSku() {
        AdmissionPolicy policyForPromotion = new AdmissionPolicy(sellerId, skuId, 4);
        AtomicInteger policyLookup = new AtomicInteger();
        when(policies.findBySellerIdAndSkuId(sellerId, skuId))
                .thenAnswer(invocation -> policyLookup.getAndIncrement() == 0
                        ? Optional.empty() : Optional.of(policyForPromotion));
        when(queueRepository.lockCounter(sellerId, skuId)).thenReturn(true);

        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(sellerId, skuId, 4));

        verify(sellerSkus).findOrderableSellerSku(sellerId, skuId, ProductSalesChannel.CAMPAIGN);
        verify(policies).save(argThat(policy ->
                policy.getSellerId().equals(sellerId) && policy.getSkuId().equals(skuId)
                        && policy.getMaxActiveCheckouts() == 4));
    }

    @Test
    void configureUpdatesExistingPolicy() {
        AdmissionPolicy existing = new AdmissionPolicy(sellerId, skuId, 2);
        when(policies.findBySellerIdAndSkuId(sellerId, skuId)).thenReturn(Optional.of(existing));
        when(queueRepository.lockCounter(sellerId, skuId)).thenReturn(true);

        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(sellerId, skuId, 5));

        assertEquals(5, existing.getMaxActiveCheckouts());
        verify(policies).save(existing);
    }

    @Test
    void configureWithoutKUsesConfiguredDefault() {
        AdmissionPolicy existing = new AdmissionPolicy(sellerId, skuId, 2);
        when(policies.findBySellerIdAndSkuId(sellerId, skuId)).thenReturn(Optional.of(existing));
        when(queueRepository.lockCounter(sellerId, skuId)).thenReturn(true);

        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(sellerId, skuId, null));

        assertEquals(20, existing.getMaxActiveCheckouts());
    }

    @Test
    void configureRejectsKAboveLimitBeforeTouchingPolicy() {
        assertThrows(AdmissionException.class, () -> admission.configureSellerSkuPolicy(
                new AdmissionRequest.ConfigureSellerSkuPolicy(sellerId, skuId, 101)));

        verify(policies, never()).save(any());
    }

    @Test
    void configurePromotesOldestWaitingTicketAfterIncreasingCapacity() {
        AdmissionPolicy existing = new AdmissionPolicy(sellerId, skuId, 1);
        UUID oldestWaitingTicket = UUID.randomUUID();
        when(policies.findBySellerIdAndSkuId(sellerId, skuId)).thenReturn(Optional.of(existing));
        when(queueRepository.lockCounter(sellerId, skuId)).thenReturn(true);
        when(tickets.countBySellerIdAndSkuIdAndCheckoutSlotHeldTrue(sellerId, skuId)).thenReturn(1L);
        when(tickets.findWaitingForSellerSku(eq(sellerId), eq(skuId), eq(TicketStatus.WAITING),
                any(Instant.class), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(oldestWaitingTicket));
        UUID waitingCampaignId = UUID.randomUUID();
        Duration waitingTicketTtl = Duration.ofMinutes(7);
        QueueTicket waitingTicket = mock(QueueTicket.class);
        when(waitingTicket.getCampaignId()).thenReturn(waitingCampaignId);
        when(tickets.findById(oldestWaitingTicket)).thenReturn(Optional.of(waitingTicket));
        when(campaigns.find(waitingCampaignId)).thenReturn(new AdmissionResponse.Campaign(
                waitingCampaignId, sellerId, skuId, CampaignStatus.OPEN, waitingTicketTtl));

        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(sellerId, skuId, 2));

        verify(queueRepository).lockCounter(sellerId, skuId);
        verify(tickets).findWaitingForSellerSku(eq(sellerId), eq(skuId), eq(TicketStatus.WAITING),
                any(Instant.class), eq(PageRequest.of(0, 1)));
        verify(queueRepository).activateWaitingTicket(eq(oldestWaitingTicket), any(Instant.class), eq(waitingTicketTtl));
    }

    @Test
    void configureRejectsUnknownSku() {
        when(sellerSkus.findOrderableSellerSku(sellerId, skuId, ProductSalesChannel.CAMPAIGN)).thenReturn(null);

        assertThrows(AdmissionException.class, () -> admission.configureSellerSkuPolicy(
                new AdmissionRequest.ConfigureSellerSkuPolicy(sellerId, skuId, 3)));
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
        UUID waitingCampaignId = UUID.randomUUID();
        Duration waitingTicketTtl = Duration.ofMinutes(7);
        QueueTicket waitingTicket = mock(QueueTicket.class);
        when(waitingTicket.getCampaignId()).thenReturn(waitingCampaignId);
        when(tickets.findById(firstWaitingTicket)).thenReturn(Optional.of(waitingTicket));
        when(campaigns.find(waitingCampaignId)).thenReturn(new AdmissionResponse.Campaign(
                waitingCampaignId, sellerId, skuId, CampaignStatus.OPEN, waitingTicketTtl));
        Method promote = AdmissionServiceImpl.class.getDeclaredMethod("promote", UUID.class, String.class);
        promote.setAccessible(true);

        promote.invoke(admission, sellerId, skuId);

        verify(tickets).findWaitingForSellerSku(eq(sellerId), eq(skuId), eq(TicketStatus.WAITING), any(Instant.class), eq(PageRequest.of(0, 2)));
        verify(queueRepository).activateWaitingTicket(eq(firstWaitingTicket), any(Instant.class), eq(waitingTicketTtl));
    }


    @Test
    void joinLocksSellerSkuCounterBeforeReadingPolicy() {
        UUID campaignId = UUID.randomUUID();
        String buyerRef = "buyer";
        String idempotencyKey = "join-key";
        UUID ticketId = UUID.randomUUID();
        when(campaigns.findOpenForAdmission(campaignId, skuId)).thenReturn(Optional.of(
                new AdmissionResponse.Campaign(campaignId, sellerId, skuId, CampaignStatus.OPEN, Duration.ofMinutes(5))));
        when(policies.findBySellerIdAndSkuId(sellerId, skuId))
                .thenReturn(Optional.of(new AdmissionPolicy(sellerId, skuId, 1)));
        when(queueRepository.lockCounter(sellerId, skuId)).thenReturn(true);
        when(queueRepository.incrementSequence(sellerId, skuId)).thenReturn(1L);
        when(tickets.countBySellerIdAndSkuIdAndCheckoutSlotHeldTrue(sellerId, skuId)).thenReturn(0L);
        when(tickets.findByCampaignIdAndIdempotencyKey(campaignId, idempotencyKey)).thenReturn(Optional.empty());
        QueueTicket savedTicket = mock(QueueTicket.class);
        when(savedTicket.getTicketId()).thenReturn(ticketId);
        when(savedTicket.getCampaignId()).thenReturn(campaignId);
        when(savedTicket.getSellerId()).thenReturn(sellerId);
        when(savedTicket.getSkuId()).thenReturn(skuId);
        when(savedTicket.getBuyerRef()).thenReturn(buyerRef);
        when(savedTicket.getSequence()).thenReturn(1L);
        when(savedTicket.getStatus()).thenReturn(TicketStatus.CHECKOUT_ACTIVE);
        when(savedTicket.getExpiresAt()).thenReturn(Instant.now().plusSeconds(60));
        when(savedTicket.isCheckoutSlotHeld()).thenReturn(true);
        when(tickets.save(any(QueueTicket.class))).thenReturn(savedTicket);
        when(tickets.findByCampaignIdAndTicketId(campaignId, ticketId)).thenReturn(Optional.of(savedTicket));

        admission.join(new AdmissionRequest.JoinQueue(campaignId, skuId, buyerRef, idempotencyKey));

        InOrder interactions = inOrder(queueRepository, policies);
        interactions.verify(queueRepository).createCounterIfAbsent(sellerId, skuId);
        interactions.verify(queueRepository).lockCounter(sellerId, skuId);
        interactions.verify(policies).findBySellerIdAndSkuId(sellerId, skuId);
    }


    @Test
    void unavailableProductRejectsJoinBeforeQueueOrIdempotencySideEffects() {
        UUID campaignId = UUID.randomUUID();
        when(campaigns.findOpenForAdmission(campaignId, skuId)).thenReturn(Optional.of(
                new AdmissionResponse.Campaign(campaignId, sellerId, skuId, CampaignStatus.OPEN, Duration.ofMinutes(5))));
        when(policies.findBySellerIdAndSkuId(sellerId, skuId))
                .thenReturn(Optional.of(new AdmissionPolicy(sellerId, skuId, 2)));
        when(sellerSkus.findOrderableSellerSku(sellerId, skuId, ProductSalesChannel.CAMPAIGN))
                .thenThrow(new CatalogException(CatalogException.Code.PRODUCT_NOT_ORDERABLE, "Unavailable"));

        assertThrows(CatalogException.class, () -> admission.join(new AdmissionRequest.JoinQueue(
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
                () -> admission.join(new AdmissionRequest.JoinQueue(campaignId, skuId, "buyer", "new-key")));

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

        admission.join(new AdmissionRequest.JoinQueue(campaignId, skuId, "buyer", key));

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
        Duration waitingTicketTtl = Duration.ofMinutes(7);
        QueueTicket waitingTicket = mock(QueueTicket.class);
        when(waitingTicket.getCampaignId()).thenReturn(campaignId);
        when(tickets.findById(waitingId)).thenReturn(Optional.of(waitingTicket));
        when(campaigns.find(campaignId)).thenReturn(new AdmissionResponse.Campaign(
                campaignId, sellerId, skuId, CampaignStatus.OPEN, waitingTicketTtl));

        admission.releaseCheckoutSlot(campaignId, ticketId, CheckoutSlotReleaseReason.PRODUCT_NOT_ORDERABLE);

        verify(tickets).releaseSlot(campaignId, ticketId, TicketStatus.CHECKOUT_FAILED);
        verify(queueRepository).activateWaitingTicket(eq(waitingId), any(Instant.class), eq(waitingTicketTtl));
    }

    @Test
    void propagatesCandidateQueryFailureToSchedulerMetrics() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("query failed");
        when(queueRepository.findDueTicketCandidates(any(Instant.class), isNull(), anyInt(), any(Duration.class)))
                .thenThrow(failure);

        assertThrows(DataAccessResourceFailureException.class, () -> admission.expireDueTickets());

        verify(queueRepository, never()).tryLockCounter(sellerId, skuId);
    }

    @Test
    void preservesNonNullExpiryCursorWhenCandidateQueryFails() {
        Instant expiresAt = Instant.now().minusSeconds(10);
        List<DueTicketCandidate> candidates =
                IntStream.range(0, 200)
                        .mapToObj(index -> new DueTicketCandidate(
                                UUID.randomUUID(), sellerId, skuId, expiresAt.plusMillis(index)))
                        .toList();
        DueTicketCursor lastCursor = candidates.get(candidates.size() - 1).cursor();
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("candidate query failed after cursor");
        when(queueRepository.findDueTicketCandidates(any(Instant.class), isNull(), anyInt(), any(Duration.class)))
                .thenReturn(candidates);
        when(queueRepository.findDueTicketCandidates(
                any(Instant.class), eq(lastCursor), anyInt(), any(Duration.class)))
                .thenThrow(failure)
                .thenReturn(List.of());
        when(queueRepository.tryLockCounter(sellerId, skuId))
                .thenReturn(QueueRepository.CounterLockResult.BUSY);

        assertEquals(0, admission.expireDueTickets());
        assertThrows(DataAccessResourceFailureException.class, () -> admission.expireDueTickets());
        assertEquals(0, admission.expireDueTickets());

        verify(queueRepository, times(2)).findDueTicketCandidates(
                any(Instant.class), eq(lastCursor), anyInt(), any(Duration.class));
    }

    @Test
    void processesFirstCandidateWhenCandidateQueryConsumesBudget() throws InterruptedException {
        UUID sellerId = UUID.randomUUID();
        String skuId = "slow-query";
        DueTicketCandidate candidate = new DueTicketCandidate(
                UUID.randomUUID(), sellerId, skuId, Instant.now().minusSeconds(1));
        when(queueRepository.findDueTicketCandidates(
                any(Instant.class), any(), anyInt(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(2_100);
                    return List.of(candidate);
                });
        when(queueRepository.tryLockCounter(sellerId, skuId))
                .thenReturn(QueueRepository.CounterLockResult.ACQUIRED);
        when(queueRepository.expireDueTicketsForSellerSku(eq(sellerId), eq(skuId), any(Instant.class)))
                .thenReturn(0);

        assertEquals(0, admission.expireDueTickets());

        verify(queueRepository).tryLockCounter(sellerId, skuId);
    }

    @Test

    void rejectsNullSellerSkuPolicyRequest() {
        AdmissionException exception = assertThrows(AdmissionException.class,
                () -> admission.configureSellerSkuPolicy(null));
        assertEquals(AdmissionException.Code.INVALID_REQUEST, exception.code());
    }

}
