package com.atlas.admission.service.impl;

import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.dto.request.CheckoutSlotReleaseReason;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.persistence.entity.QueueTicket;
import com.atlas.admission.persistence.repository.TicketRepository;
import com.atlas.admission.persistence.repository.JdbcQueueRepository;
import com.atlas.campaign.service.CampaignService;
import com.atlas.campaign.dto.response.CampaignResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class AdmissionServiceImpl implements AdmissionService {
    private final TicketRepository tickets;
    private final JdbcQueueRepository queueRepository;
    private final CampaignService campaigns;
    private final Clock clock;
    private final Supplier<UUID> ids;

    @Autowired
    public AdmissionServiceImpl(TicketRepository tickets, JdbcQueueRepository queueRepository,
            CampaignService campaigns, Clock clock) {
        this(tickets, queueRepository, campaigns, clock, UUID::randomUUID);
    }

    AdmissionServiceImpl(TicketRepository tickets, JdbcQueueRepository queueRepository, CampaignService campaigns,
            Clock clock, Supplier<UUID> ids) {
        this.tickets = tickets;
        this.queueRepository = queueRepository;
        this.campaigns = campaigns;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    @Transactional
    public QueueTicketResponse join(JoinQueueRequest request) {
        if (request == null || request.campaignId() == null || blank(request.skuId())
                || request.skuId().length() > 128 || blank(request.buyerRef()) || request.buyerRef().length() > 256
                || blank(request.idempotencyKey()) || request.idempotencyKey().length() > 256) {
            throw new AdmissionException(AdmissionException.Code.INVALID_REQUEST, "Invalid queue join request");
        }
        QueueTicketResponse prior = findByKey(request.campaignId(), request.idempotencyKey());
        if (prior != null) {
            if (!lockCounter(request.campaignId())) throw new IllegalStateException("Admission counter is missing for an existing ticket");
            prior = findByKey(request.campaignId(), request.idempotencyKey());
            if (prior != null) {
                requireSamePayload(prior, request);
                expireDueTicketsForCampaign(request.campaignId(), clock.instant());
                return requireTicket(request.campaignId(), prior.ticketId());
            }
        }
        CampaignResponse campaign = campaigns.requireOpen(request.campaignId(), request.skuId());
        queueRepository.createCounterIfAbsent(request.campaignId());
        if (!lockCounter(request.campaignId())) throw new IllegalStateException("Admission counter initialization failed");
        Instant now = clock.instant();
        expireDueTicketsForCampaign(request.campaignId(), now);
        prior = findByKey(request.campaignId(), request.idempotencyKey());
        if (prior != null) {
            requireSamePayload(prior, request);
            return requireTicket(request.campaignId(), prior.ticketId());
        }
        long sequence = queueRepository.incrementSequence(request.campaignId());
        long active = tickets.countByCampaignIdAndCheckoutSlotHeldTrue(request.campaignId());
        boolean slot = active < campaign.maxActiveCheckouts();
        UUID ticketId = ids.get();
        Instant expiresAt = now.plus(campaign.queueTicketTtl());
        tickets.save(new QueueTicket(ticketId, request.campaignId(), request.skuId(), request.buyerRef(),
                request.idempotencyKey(), sequence,
                slot ? TicketStatus.CHECKOUT_ACTIVE : TicketStatus.WAITING, expiresAt, now,
                slot ? now : null, slot));
        return requireTicket(request.campaignId(), ticketId);
    }

    @Override
    @Transactional(readOnly = true)
    public QueueTicketResponse find(UUID campaignId, UUID ticketId) {
        requireTicketKey(campaignId, ticketId);
        return tickets.findByCampaignIdAndTicketId(campaignId, ticketId)
                .map(AdmissionServiceImpl::toResponse).orElse(null);
    }

    @Override
    @Transactional
    public QueueTicketResponse requireCheckoutSlot(UUID campaignId, UUID ticketId) {
        requireTicketKey(campaignId, ticketId);
        if (!lockCounter(campaignId)) throw ticketNotFound(campaignId, ticketId);
        expireDueTicketsForCampaign(campaignId, clock.instant());
        QueueTicketResponse ticket = requireTicket(campaignId, ticketId);
        if (!ticket.checkoutSlotHeld() || (ticket.status() != TicketStatus.CHECKOUT_ACTIVE && ticket.status() != TicketStatus.CHECKOUT_STARTED)) {
            throw new AdmissionException(AdmissionException.Code.CHECKOUT_SLOT_UNAVAILABLE,
                    "Ticket does not hold an available checkout slot: " + campaignId + "/" + ticketId);
        }
        return ticket;
    }

    @Override
    @Transactional
    public QueueTicketResponse startCheckout(UUID campaignId, UUID ticketId) {
        requireTicketKey(campaignId, ticketId);
        if (!lockCounter(campaignId)) throw ticketNotFound(campaignId, ticketId);
        expireDueTicketsForCampaign(campaignId, clock.instant());
        QueueTicketResponse ticket = requireTicket(campaignId, ticketId);
        if (ticket.status() == TicketStatus.CHECKOUT_ACTIVE && ticket.checkoutSlotHeld()) {
            tickets.updateStatus(campaignId, ticketId, TicketStatus.CHECKOUT_STARTED);
        } else if (!(ticket.status() == TicketStatus.CHECKOUT_STARTED && ticket.checkoutSlotHeld())) {
            throw new AdmissionException(AdmissionException.Code.CHECKOUT_SLOT_UNAVAILABLE,
                    "Ticket does not hold an available checkout slot: " + campaignId + "/" + ticketId);
        }
        return requireTicket(campaignId, ticketId);
    }

    @Override
    @Transactional
    public QueueTicketResponse releaseCheckoutSlot(UUID campaignId, UUID ticketId, CheckoutSlotReleaseReason reason) {
        requireTicketKey(campaignId, ticketId);
        if (reason == null) throw new AdmissionException(AdmissionException.Code.INVALID_REQUEST, "Release reason is required");
        if (!lockCounter(campaignId)) throw ticketNotFound(campaignId, ticketId);
        expireDueTicketsForCampaign(campaignId, clock.instant());
        QueueTicketResponse ticket = requireTicket(campaignId, ticketId);
        if (!ticket.checkoutSlotHeld()) return ticket;
        TicketStatus status = switch (reason) {
            case INITIAL_AUTHORIZE_SUBMITTED -> TicketStatus.CHECKOUT_SUBMITTED;
            case CHECKOUT_WINDOW_EXPIRED -> TicketStatus.EXPIRED;
            case INVENTORY_UNAVAILABLE, BUYER_CANCELLED -> TicketStatus.CHECKOUT_FAILED;
        };
        tickets.releaseSlot(campaignId, ticketId, status);
        promote(campaignId);
        return requireTicket(campaignId, ticketId);
    }

    @Scheduled(fixedDelayString = "${atlas.admission.expiry-poll-interval:PT1S}")
    @Transactional
    public int expireDueTickets() {
        Instant now = clock.instant();
        List<UUID> campaignsToProcess = tickets.findDueCampaignIds(
                List.of(TicketStatus.WAITING, TicketStatus.CHECKOUT_ACTIVE), now);
        int expired = 0;
        for (UUID campaignId : campaignsToProcess) {
            if (!lockCounter(campaignId)) throw new IllegalStateException("Admission counter is missing for expired tickets");
            expired += expireDueTicketsForCampaign(campaignId, now);
        }
        return expired;
    }

    private int expireDueTicketsForCampaign(UUID campaignId, Instant now) {
        int expired = queueRepository.expireDueTicketsForCampaign(campaignId, now);
        if (expired > 0) promote(campaignId);
        return expired;
    }

    private QueueTicketResponse findByKey(UUID campaignId, String key) {
        return tickets.findByCampaignIdAndIdempotencyKey(campaignId, key).map(AdmissionServiceImpl::toResponse).orElse(null);
    }

    private QueueTicketResponse requireSamePayload(QueueTicketResponse prior, JoinQueueRequest request) {
        if (!tickets.existsByCampaignIdAndTicketIdAndSkuIdAndBuyerRef(request.campaignId(), prior.ticketId(), request.skuId(), request.buyerRef())) {
            throw new AdmissionException(AdmissionException.Code.IDEMPOTENCY_PAYLOAD_MISMATCH,
                    "Idempotency key was already used with a different payload: "
                            + request.campaignId() + "/" + request.idempotencyKey());
        }
        return prior;
    }

    private boolean lockCounter(UUID campaignId) { return queueRepository.lockCounter(campaignId); }

    private QueueTicketResponse requireTicket(UUID campaignId, UUID ticketId) {
        QueueTicketResponse ticket = find(campaignId, ticketId);
        if (ticket == null) throw ticketNotFound(campaignId, ticketId);
        return ticket;
    }

    private static AdmissionException ticketNotFound(UUID campaignId, UUID ticketId) {
        return new AdmissionException(AdmissionException.Code.TICKET_NOT_FOUND,
                "Unknown ticket: " + campaignId + "/" + ticketId);
    }

    private static void requireTicketKey(UUID campaignId, UUID ticketId) {
        if (campaignId == null || ticketId == null) {
            throw new AdmissionException(AdmissionException.Code.INVALID_REQUEST, "Campaign and ticket IDs are required");
        }
    }
    private void promote(UUID campaignId) {
        CampaignResponse campaign = campaigns.find(campaignId);
        if (campaign == null) return;
        long active = tickets.countByCampaignIdAndCheckoutSlotHeldTrue(campaignId);
        int slots = campaign.maxActiveCheckouts() - Math.toIntExact(active);
        if (slots <= 0) return;
        Instant now = clock.instant();
        List<UUID> waiting = queueRepository.findWaitingTicketIdsForPromotion(campaignId, now, slots);
        for (UUID ticketId : waiting) queueRepository.activateWaitingTicket(campaignId, ticketId, now);
    }

    private static QueueTicketResponse toResponse(QueueTicket entity) {
        return new QueueTicketResponse(entity.getTicketId(), entity.getCampaignId(), entity.getSkuId(), entity.getBuyerRef(),
                entity.getSequence(), entity.getStatus(), entity.getExpiresAt(), entity.isCheckoutSlotHeld());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
