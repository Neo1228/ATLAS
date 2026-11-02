package com.atlas.admission.persistence.repository;

import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.persistence.entity.QueueTicket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<QueueTicket, UUID> {
    Optional<QueueTicket> findByCampaignIdAndTicketId(UUID campaignId, UUID ticketId);
    Optional<QueueTicket> findByCampaignIdAndIdempotencyKey(UUID campaignId, String idempotencyKey);
    boolean existsByCampaignIdAndTicketIdAndSkuIdAndBuyerRef(UUID campaignId, UUID ticketId, String skuId, String buyerRef);
    long countByCampaignIdAndCheckoutSlotHeldTrue(UUID campaignId);

    @Query("select distinct t.campaignId from QueueTicket t where t.status in :statuses and t.expiresAt <= :expiresAt order by t.campaignId")
    List<UUID> findDueCampaignIds(@Param("statuses") List<TicketStatus> statuses, @Param("expiresAt") Instant expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update QueueTicket t set t.status = :status, t.expiresAt = null where t.campaignId = :campaignId and t.ticketId = :ticketId")
    int updateStatus(@Param("campaignId") UUID campaignId, @Param("ticketId") UUID ticketId, @Param("status") TicketStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update QueueTicket t set t.status = :status, t.checkoutSlotHeld = false, t.expiresAt = null where t.campaignId = :campaignId and t.ticketId = :ticketId")
    int releaseSlot(@Param("campaignId") UUID campaignId, @Param("ticketId") UUID ticketId,
            @Param("status") TicketStatus status);
}
