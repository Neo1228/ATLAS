package com.atlas.admission.repository;

import com.atlas.admission.model.TicketStatus;
import com.atlas.admission.persistence.entity.QueueTicket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<QueueTicket, UUID> {
    Optional<QueueTicket> findByCampaignIdAndTicketId(UUID campaignId, UUID ticketId);
    Optional<QueueTicket> findByCampaignIdAndIdempotencyKey(UUID campaignId, String idempotencyKey);
    boolean existsByCampaignIdAndTicketIdAndSkuIdAndBuyerRef(UUID campaignId, UUID ticketId, String skuId, String buyerRef);
    /**
     * Seller-SKU counter 행 잠금 안에서 부른다. {@code checkout_slot_held}는 두 checkout 상태와 같다는 CHECK 제약
     * ({@code admission_ticket_slot_status_consistent})이 있으므로 상태 조건을 함께 걸어 V4 부분 인덱스로 살아 있는 ticket만
     * 읽는다. 상태 조건이 없으면 그 SKU에 남은 종료 ticket 이력 전체를 읽었다(EXP-115 H7: 보존 ticket 7,937개에서 4,115 → 2 버퍼).
     */
    @Query("select count(t) from QueueTicket t where t.sellerId = :sellerId and t.skuId = :skuId and t.checkoutSlotHeld = true"
            + " and t.status in (com.atlas.admission.model.TicketStatus.CHECKOUT_ACTIVE,"
            + " com.atlas.admission.model.TicketStatus.CHECKOUT_STARTED)")
    long countBySellerIdAndSkuIdAndCheckoutSlotHeldTrue(@Param("sellerId") UUID sellerId, @Param("skuId") String skuId);

    @Query("select t.ticketId from QueueTicket t where t.sellerId = :sellerId and t.skuId = :skuId and t.status = :status and t.expiresAt > :now order by t.sequence")
    List<UUID> findWaitingForSellerSku(@Param("sellerId") UUID sellerId, @Param("skuId") String skuId,
            @Param("status") TicketStatus status, @Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update QueueTicket t set t.status = :status, t.expiresAt = null, t.checkoutStartedAt = :startedAt where t.campaignId = :campaignId and t.ticketId = :ticketId")
    int markCheckoutStarted(@Param("campaignId") UUID campaignId, @Param("ticketId") UUID ticketId,
            @Param("status") TicketStatus status, @Param("startedAt") Instant startedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update QueueTicket t set t.status = :status, t.checkoutSlotHeld = false, t.expiresAt = null where t.campaignId = :campaignId and t.ticketId = :ticketId")
    int releaseSlot(@Param("campaignId") UUID campaignId, @Param("ticketId") UUID ticketId,
            @Param("status") TicketStatus status);
}
