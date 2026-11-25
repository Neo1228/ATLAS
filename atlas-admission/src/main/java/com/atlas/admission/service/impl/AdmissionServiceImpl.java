package com.atlas.admission.service.impl;

import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.persistence.projection.DueTicketCandidate;
import com.atlas.admission.persistence.projection.DueTicketCursor;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.service.AdmissionExpirySweepPolicy;
import com.atlas.admission.service.AdmissionLimits;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.model.CheckoutSlotReleaseReason;
import com.atlas.admission.model.TicketStatus;
import com.atlas.admission.persistence.entity.QueueTicket;
import com.atlas.admission.repository.TicketRepository;
import com.atlas.admission.repository.QueueRepository;
import com.atlas.admission.repository.AdmissionPolicyRepository;
import com.atlas.admission.persistence.entity.AdmissionPolicy;
import com.atlas.admission.exception.CampaignException;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.catalog.model.ProductSalesChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AdmissionServiceImpl implements AdmissionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdmissionServiceImpl.class);
    private final TicketRepository tickets;
    private final QueueRepository queueRepository;
    private final CampaignService campaigns;
    private final AdmissionPolicyRepository policies;
    private final SellerSkuService sellerSkus;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private final AdmissionExpirySweepPolicy expirySweepPolicy;
    private final AdmissionLimits limits;
    private DueTicketCursor expiryCursor;

    public AdmissionServiceImpl(TicketRepository tickets, QueueRepository queueRepository,
            CampaignService campaigns, AdmissionPolicyRepository policies, SellerSkuService sellerSkus,
            Clock clock, PlatformTransactionManager transactionManager, AdmissionLimits limits,
            @Value("${atlas.admission.expiry-sweep-batch-size:100}") int expiryBatchSize,
            @Value("${atlas.admission.expiry-sweep-time-budget:PT2S}") Duration expiryTimeBudget) {
        this.tickets = Objects.requireNonNull(tickets);
        this.queueRepository = Objects.requireNonNull(queueRepository);
        this.campaigns = Objects.requireNonNull(campaigns);
        this.policies = Objects.requireNonNull(policies);
        this.sellerSkus = Objects.requireNonNull(sellerSkus);
        this.clock = Objects.requireNonNull(clock);
        this.transactionManager = Objects.requireNonNull(transactionManager);
        this.limits = Objects.requireNonNull(limits);
        this.expirySweepPolicy = new AdmissionExpirySweepPolicy(expiryBatchSize, expiryTimeBudget);
    }

    @Override
    @Transactional
    public void configureSellerSkuPolicy(AdmissionRequest.ConfigureSellerSkuPolicy request) {
        if (request == null) throw new AdmissionException(AdmissionException.Code.INVALID_REQUEST,
                "Seller-SKU admission policy request is required");
        UUID sellerId = request.sellerId();
        String skuId = request.skuId();
        int maxActiveCheckouts = limits.resolveMaxActiveCheckouts(request.maxActiveCheckouts());
        if (sellerId == null || blank(skuId) || skuId.length() > 128
                || !limits.allowsMaxActiveCheckouts(maxActiveCheckouts)) {
            throw new AdmissionException(AdmissionException.Code.INVALID_REQUEST, "Invalid seller-SKU admission policy");
        }
        if (sellerSkus.findOrderableSellerSku(sellerId, skuId, ProductSalesChannel.CAMPAIGN) == null) {
            throw new AdmissionException(AdmissionException.Code.INVALID_REQUEST, "Unknown Seller-SKU");
        }
        queueRepository.createCounterIfAbsent(sellerId, skuId);
        if (!lockCounter(sellerId, skuId)) {
            throw new IllegalStateException("Admission counter initialization failed");
        }
        AdmissionPolicy policy = policies.findBySellerIdAndSkuId(sellerId, skuId)
                .orElseGet(() -> new AdmissionPolicy(sellerId, skuId, maxActiveCheckouts));
        policy.setMaxActiveCheckouts(maxActiveCheckouts);
        policies.save(policy);
        Instant now = clock.instant();
        queueRepository.expireDueTicketsForSellerSku(sellerId, skuId, now);
        promote(sellerId, skuId, now);
    }

    @Transactional
    public AdmissionResponse.QueueTicket join(AdmissionRequest.JoinQueue request) {
        if (request == null || request.campaignId() == null || blank(request.skuId())
                || request.skuId().length() > 128 || blank(request.buyerRef()) || request.buyerRef().length() > 256
                || blank(request.idempotencyKey()) || request.idempotencyKey().length() > 256) {
            throw new AdmissionException(AdmissionException.Code.INVALID_REQUEST, "Invalid queue join request");
        }
        AdmissionResponse.QueueTicket prior = findByKey(request.campaignId(), request.idempotencyKey());
        if (prior != null) return replayPrior(prior, request);
        Optional<AdmissionResponse.Campaign> openCampaign = campaigns.findOpenForAdmission(
                request.campaignId(), request.skuId());
        if (openCampaign.isEmpty()) {
            AdmissionResponse.QueueTicket concurrentPrior = findByKey(request.campaignId(), request.idempotencyKey());
            if (concurrentPrior != null) return replayPrior(concurrentPrior, request);
            throw new CampaignException(CampaignException.Code.NOT_ELIGIBLE,
                    "Campaign is not open SKU " + request.skuId() + ": " + request.campaignId());
        }
        AdmissionResponse.Campaign campaign = openCampaign.get();
        requireSellerSku(campaign);
        queueRepository.createCounterIfAbsent(campaign.sellerId(), campaign.skuId());
        if (!lockCounter(campaign.sellerId(), campaign.skuId())) {
            throw new IllegalStateException("Admission counter initialization failed");
        }
        AdmissionPolicy policy = requirePolicy(campaign.sellerId(), campaign.skuId());
        Instant now = clock.instant();
        expireDueTicketsForSellerSku(campaign.sellerId(), campaign.skuId(), now);
        AdmissionResponse.QueueTicket concurrentPrior = findByKey(request.campaignId(), request.idempotencyKey());
        if (concurrentPrior != null) {
            requireSamePayload(concurrentPrior, request);
            return requireTicket(request.campaignId(), concurrentPrior.ticketId());
        }
        // 같은 구매자의 live ticket 수는 counter 잠금 안에서 센다: 이 Seller-SKU의 다른 join이 검사와 삽입 사이에 끼어들 수 없다.
        if (limits.maxLiveTicketsPerBuyer() > 0 && queueRepository.countLiveTickets(
                campaign.sellerId(), campaign.skuId(), request.buyerRef()) >= limits.maxLiveTicketsPerBuyer()) {
            throw new AdmissionException(AdmissionException.Code.LIVE_TICKET_LIMIT_REACHED,
                    "Buyer already holds a live ticket for this Seller-SKU: " + request.skuId());
        }
        long sequence = queueRepository.incrementSequence(campaign.sellerId(), campaign.skuId());
        long active = tickets.countBySellerIdAndSkuIdAndCheckoutSlotHeldTrue(campaign.sellerId(), campaign.skuId());
        boolean slot = active < policy.getMaxActiveCheckouts();
        Instant expiresAt = now.plus(campaign.queueTicketTtl());
        QueueTicket ticket = tickets.save(new QueueTicket(request.campaignId(), campaign.sellerId(), request.skuId(),
                request.buyerRef(), request.idempotencyKey(), sequence,
                slot ? TicketStatus.CHECKOUT_ACTIVE : TicketStatus.WAITING,
                expiresAt, now, slot ? now : null, slot));
        return requireTicket(request.campaignId(), ticket.getTicketId());

    }

    private AdmissionResponse.QueueTicket replayPrior(AdmissionResponse.QueueTicket prior, AdmissionRequest.JoinQueue request) {
        requireSamePayload(prior, request);
        queueRepository.createCounterIfAbsent(prior.sellerId(), prior.skuId());
        if (!lockCounter(prior.sellerId(), prior.skuId())) {
            throw new IllegalStateException("Admission counter initialization failed");
        }
        expireDueTicketsForSellerSku(prior.sellerId(), prior.skuId(), clock.instant());
        return requireTicket(request.campaignId(), prior.ticketId());
    }

    private CatalogResponse.SellerSku requireSellerSku(AdmissionResponse.Campaign campaign) {
        CatalogResponse.SellerSku sellerSku = sellerSkus.findOrderableSellerSku(
                campaign.sellerId(), campaign.skuId(), ProductSalesChannel.CAMPAIGN);
        if (sellerSku == null) throw new AdmissionException(AdmissionException.Code.INVALID_REQUEST, "Campaign Seller-SKU is not registered");
        return sellerSku;
    }
    private AdmissionPolicy requirePolicy(UUID sellerId, String skuId) {
        return policies.findBySellerIdAndSkuId(sellerId, skuId)
                .orElseThrow(() -> new AdmissionException(AdmissionException.Code.INVALID_REQUEST, "Seller-SKU admission policy is not configured"));
    }


    @Override
    @Transactional(readOnly = true)
    public AdmissionResponse.QueueTicket find(UUID campaignId, UUID ticketId) {
        return tickets.findByCampaignIdAndTicketId(campaignId, ticketId)
                .map(AdmissionServiceImpl::toResponse)
                .orElseThrow(() -> new AdmissionException(AdmissionException.Code.TICKET_NOT_FOUND,
                        "Ticket not found: " + ticketId + " in campaign: " + campaignId));
    }

    @Override
    @Transactional
    public int purgeTombstones(Duration retention, int batchSize) {
        return queueRepository.purgeTombstones(clock.instant().minus(retention), batchSize);
    }

    @Override
    @Transactional
    public AdmissionResponse.QueueTicket requireCheckoutSlot(UUID campaignId, UUID ticketId) {
        requireTicketKey(campaignId, ticketId);
        AdmissionResponse.QueueTicket ticket = requireTicket(campaignId, ticketId);
        if (!lockCounter(ticket.sellerId(), ticket.skuId())) throw ticketNotFound(campaignId, ticketId);
        expireDueTicketsForSellerSku(ticket.sellerId(), ticket.skuId(), clock.instant());
        ticket = requireTicket(campaignId, ticketId);
        if (!ticket.checkoutSlotHeld() || (ticket.status() != TicketStatus.CHECKOUT_ACTIVE && ticket.status() != TicketStatus.CHECKOUT_STARTED)) {
            throw new AdmissionException(AdmissionException.Code.CHECKOUT_SLOT_UNAVAILABLE, "Ticket does not hold an available checkout slot: " + campaignId + "/" + ticketId);
        }
        return ticket;
    }

    @Override
    @Transactional
    public AdmissionResponse.QueueTicket startCheckout(UUID campaignId, UUID ticketId) {
        requireTicketKey(campaignId, ticketId);
        AdmissionResponse.QueueTicket ticket = requireTicket(campaignId, ticketId);
        if (!lockCounter(ticket.sellerId(), ticket.skuId())) throw ticketNotFound(campaignId, ticketId);
        expireDueTicketsForSellerSku(ticket.sellerId(), ticket.skuId(), clock.instant());
        ticket = requireTicket(campaignId, ticketId);
        if (ticket.status() == TicketStatus.CHECKOUT_ACTIVE && ticket.checkoutSlotHeld()) {
            tickets.markCheckoutStarted(campaignId, ticketId, TicketStatus.CHECKOUT_STARTED, clock.instant());
        } else if (!(ticket.status() == TicketStatus.CHECKOUT_STARTED && ticket.checkoutSlotHeld())) {
            throw new AdmissionException(AdmissionException.Code.CHECKOUT_SLOT_UNAVAILABLE, "Ticket does not hold an available checkout slot: " + campaignId + "/" + ticketId);
        }
        return requireTicket(campaignId, ticketId);
    }

    @Override
    @Transactional
    public AdmissionResponse.QueueTicket releaseCheckoutSlot(UUID campaignId, UUID ticketId, CheckoutSlotReleaseReason reason) {
        requireTicketKey(campaignId, ticketId);
        if (reason == null) throw new AdmissionException(AdmissionException.Code.INVALID_REQUEST, "Release reason is required");
        AdmissionResponse.QueueTicket ticket = requireTicket(campaignId, ticketId);
        if (!lockCounter(ticket.sellerId(), ticket.skuId())) throw ticketNotFound(campaignId, ticketId);
        expireDueTicketsForSellerSku(ticket.sellerId(), ticket.skuId(), clock.instant());
        ticket = requireTicket(campaignId, ticketId);
        if (!ticket.checkoutSlotHeld()) return ticket;
        TicketStatus status = switch (reason) {
            case INITIAL_AUTHORIZE_SUBMITTED -> TicketStatus.CHECKOUT_SUBMITTED;
            case CHECKOUT_WINDOW_EXPIRED -> TicketStatus.EXPIRED;
            case INVENTORY_UNAVAILABLE, BUYER_CANCELLED, PRODUCT_NOT_ORDERABLE -> TicketStatus.CHECKOUT_FAILED;
        };
        tickets.releaseSlot(campaignId, ticketId, status);
        promote(ticket.sellerId(), ticket.skuId());
        return requireTicket(campaignId, ticketId);
    }

    @Override
    public synchronized int expireDueTickets() {
        Instant now = clock.instant();
        long deadline = System.nanoTime() + expirySweepPolicy.timeBudget().toNanos();
        List<DueTicketCandidate> candidates;
        candidates = queueRepository.findDueTicketCandidates(
                now, expiryCursor, expirySweepPolicy.candidateWindowSize(), expirySweepPolicy.timeBudget());
        if (candidates.isEmpty()) {
            expiryCursor = null;
            return 0;
        }

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Set<SellerSkuKey> visitedPairs = new HashSet<>();
        int attemptedPairs = 0;
        int consumedRows = 0;
        int expired = 0;
        DueTicketCursor lastConsumed = expiryCursor;
        for (DueTicketCandidate candidate : candidates) {
            if (System.nanoTime() >= deadline && consumedRows > 0) break;
            SellerSkuKey pair = new SellerSkuKey(candidate.sellerId(), candidate.skuId());
            if (visitedPairs.add(pair)) {
                if (attemptedPairs >= expirySweepPolicy.batchSize()) break;
                attemptedPairs++;
                try {
                    transactionTemplate.setTimeout(timeoutSecondsUntil(deadline));
                    Integer changed = transactionTemplate.execute(status -> {
                        QueueRepository.CounterLockResult lockResult = queueRepository
                                .tryLockCounter(pair.sellerId(), pair.skuId());
                        if (lockResult == QueueRepository.CounterLockResult.BUSY) return -1;
                        if (lockResult == QueueRepository.CounterLockResult.MISSING) {
                            throw new MissingAdmissionCounterException();
                        }
                        return expireDueTicketCandidates(
                                pair.sellerId(), pair.skuId(),
                                candidates.stream()
                                        .filter(dueCandidate -> dueCandidate.sellerId().equals(pair.sellerId())
                                                && dueCandidate.skuId().equals(pair.skuId()))
                                        .map(DueTicketCandidate::ticketId)
                                        .toList(),
                                now);
                    });
                    expired += changed == null || changed < 0 ? 0 : changed;
                } catch (MissingAdmissionCounterException exception) {
                    LOGGER.error("Admission expiry candidate has no counter; sellerId={} skuId={}",
                            pair.sellerId(), pair.skuId());
                } catch (RuntimeException exception) {
                    LOGGER.warn("Admission expiry failed for seller-SKU; failureType={}",
                            exception.getClass().getSimpleName());
                }
            }
            lastConsumed = candidate.cursor();
            consumedRows++;
        }
        expiryCursor = lastConsumed;
        if (consumedRows == candidates.size() && candidates.size() < expirySweepPolicy.candidateWindowSize()) {
            expiryCursor = null;
        }
        return expired;
    }

    private int timeoutSecondsUntil(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        long remainingSeconds = Duration.ofNanos(Math.max(0, remainingNanos)).toSeconds();
        return Math.max(1, Math.min(
                Math.toIntExact(expirySweepPolicy.timeBudget().toSeconds()),
                Math.toIntExact(remainingSeconds)));
    }


    private static final class MissingAdmissionCounterException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private record SellerSkuKey(UUID sellerId, String skuId) { }

    private int expireDueTicketsForSellerSku(UUID sellerId, String skuId, Instant now) {
        int expired = queueRepository.expireDueTicketsForSellerSku(sellerId, skuId, now);
        if (expired > 0) promote(sellerId, skuId);
        return expired;
    }

    private int expireDueTicketCandidates(UUID sellerId, String skuId, List<UUID> ticketIds, Instant now) {
        int expired = queueRepository.expireDueTicketCandidates(sellerId, skuId, ticketIds, now);
        if (expired > 0) promote(sellerId, skuId);
        return expired;
    }

    private AdmissionResponse.QueueTicket findByKey(UUID campaignId, String key) {
        return tickets.findByCampaignIdAndIdempotencyKey(campaignId, key).map(AdmissionServiceImpl::toResponse).orElse(null);
    }

    private AdmissionResponse.QueueTicket requireSamePayload(AdmissionResponse.QueueTicket prior, AdmissionRequest.JoinQueue request) {
        if (!tickets.existsByCampaignIdAndTicketIdAndSkuIdAndBuyerRef(request.campaignId(), prior.ticketId(), request.skuId(), request.buyerRef())) {
            throw new AdmissionException(AdmissionException.Code.IDEMPOTENCY_PAYLOAD_MISMATCH, "Idempotency key was already used with a different payload: " + request.campaignId() + "/" + request.idempotencyKey());
        }
        return prior;
    }

    private boolean lockCounter(UUID sellerId, String skuId) { return queueRepository.lockCounter(sellerId, skuId); }

    private AdmissionResponse.QueueTicket requireTicket(UUID campaignId, UUID ticketId) {
        AdmissionResponse.QueueTicket ticket = find(campaignId, ticketId);
        if (ticket == null) throw ticketNotFound(campaignId, ticketId);
        return ticket;
    }

    private static AdmissionException ticketNotFound(UUID campaignId, UUID ticketId) {
        return new AdmissionException(AdmissionException.Code.TICKET_NOT_FOUND, "Unknown ticket: " + campaignId + "/" + ticketId);
    }

    private static void requireTicketKey(UUID campaignId, UUID ticketId) {
        if (campaignId == null || ticketId == null) throw new AdmissionException(AdmissionException.Code.INVALID_REQUEST, "Campaign and ticket IDs are required");
    }

    private void promote(UUID sellerId, String skuId) {
        promote(sellerId, skuId, clock.instant());
    }

    private void promote(UUID sellerId, String skuId, Instant now) {
        long active = tickets.countBySellerIdAndSkuIdAndCheckoutSlotHeldTrue(sellerId, skuId);
        int slots = requirePolicyForPromotion(sellerId, skuId) - Math.toIntExact(active);
        if (slots <= 0) return;
        List<UUID> waiting = tickets.findWaitingForSellerSku(sellerId, skuId, TicketStatus.WAITING, now, PageRequest.of(0, slots));
        // 승격된 ticket의 checkout 창은 slot 획득 시점부터 시작합니다. join 즉시 slot을 얻은 ticket과 같이
        // Campaign ticket TTL을 쓰며, 전역 FIFO는 여러 Campaign ticket을 섞어 승격하므로 ticket별 Campaign 값을 씁니다.
        Map<UUID, Duration> checkoutSlotWindows = new HashMap<>();
        for (UUID ticketId : waiting) {
            Duration checkoutSlotWindow = checkoutSlotWindows.computeIfAbsent(requireWaitingTicketCampaignId(ticketId),
                    campaignId -> campaigns.find(campaignId).queueTicketTtl());
            queueRepository.activateWaitingTicket(ticketId, now, checkoutSlotWindow);
        }
    }

    private UUID requireWaitingTicketCampaignId(UUID ticketId) {
        return tickets.findById(ticketId).map(QueueTicket::getCampaignId)
                .orElseThrow(() -> new IllegalStateException("Waiting ticket disappeared during promotion: " + ticketId));
    }

    private int requirePolicyForPromotion(UUID sellerId, String skuId) {
        return requirePolicy(sellerId, skuId).getMaxActiveCheckouts();
    }

    private static AdmissionResponse.QueueTicket toResponse(QueueTicket entity) {
        return new AdmissionResponse.QueueTicket(entity.getTicketId(), entity.getCampaignId(), entity.getSellerId(), entity.getSkuId(), entity.getBuyerRef(), entity.getSequence(), entity.getStatus(), entity.getExpiresAt(), entity.isCheckoutSlotHeld());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
