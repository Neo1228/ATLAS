package com.atlas.admission;

import java.util.UUID;

public interface AdmissionApi {

    QueueTicketView join(JoinQueueCommand command);

    QueueTicketView find(UUID campaignId, UUID ticketId);

    QueueTicketView requireCheckoutSlot(UUID campaignId, UUID ticketId);
    QueueTicketView startCheckout(UUID campaignId, UUID ticketId);
    QueueTicketView releaseCheckoutSlot(UUID campaignId, UUID ticketId, CheckoutSlotReleaseReason reason);

    int expireDueTickets();
}
