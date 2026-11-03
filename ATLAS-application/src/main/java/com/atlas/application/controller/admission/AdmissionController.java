package com.atlas.application.controller.admission;

import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.application.controller.dto.JoinQueueHttpRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/campaigns/{campaignId}/tickets")
public class AdmissionController {

    private final AdmissionService admission;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QueueTicketResponse join(@PathVariable UUID campaignId,
                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                @Valid @RequestBody JoinQueueHttpRequest request) {
        return admission.join(new JoinQueueRequest(campaignId, request.skuId(), request.buyerRef(), idempotencyKey));
    }

    @GetMapping("/{ticketId}")
    public QueueTicketResponse find(@PathVariable UUID campaignId, @PathVariable UUID ticketId) {
        QueueTicketResponse ticket = admission.find(campaignId, ticketId);
        if (ticket == null) throw new AdmissionException(AdmissionException.Code.TICKET_NOT_FOUND,
                "Unknown ticket: " + campaignId + "/" + ticketId);
        return ticket;
    }
}
