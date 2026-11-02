package com.atlas.admission.web;

import com.atlas.admission.AdmissionApi;
import com.atlas.admission.AdmissionTicketNotFoundException;
import com.atlas.admission.JoinQueueCommand;
import com.atlas.admission.QueueTicketView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/campaigns/{campaignId}/tickets")
public class AdmissionController {

    public record JoinQueueRequest(
            @NotBlank @Size(max = 128) String skuId,
            @NotBlank @Size(max = 256) String buyerRef) { }

    private final AdmissionApi admission;

    public AdmissionController(AdmissionApi admission) {
        this.admission = admission;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QueueTicketView join(@PathVariable UUID campaignId,
                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                @Valid @RequestBody JoinQueueRequest request) {
        return admission.join(new JoinQueueCommand(campaignId, request.skuId(), request.buyerRef(), idempotencyKey));
    }

    @GetMapping("/{ticketId}")
    public QueueTicketView find(@PathVariable UUID campaignId, @PathVariable UUID ticketId) {
        QueueTicketView ticket = admission.find(campaignId, ticketId);
        if (ticket == null) throw new AdmissionTicketNotFoundException(campaignId, ticketId);
        return ticket;
    }
}
