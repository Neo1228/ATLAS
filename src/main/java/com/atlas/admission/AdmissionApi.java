package com.atlas.admission;

import java.util.UUID;

public interface AdmissionApi {

    QueueTicketView join(JoinQueueCommand command);

    /** 마지막으로 저장된 Ticket 상태를 반환. 만료·승격은 스케줄러나 상태 변경 작업이 반영. */
    QueueTicketView find(UUID campaignId, UUID ticketId);

    QueueTicketView requireCheckoutSlot(UUID campaignId, UUID ticketId);
    QueueTicketView startCheckout(UUID campaignId, UUID ticketId);
    QueueTicketView releaseCheckoutSlot(UUID campaignId, UUID ticketId, CheckoutSlotReleaseReason reason);

}
