package com.atlas.admission.internal;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdmissionExpiryScheduler {

    private final AdmissionService admission;

    AdmissionExpiryScheduler(AdmissionService admission) {
        this.admission = admission;
    }

    @Scheduled(fixedDelayString = "${atlas.admission.expiry-poll-interval:PT1S}")
    public int expireDueTickets() {
        return admission.expireDueTickets();
    }
}
