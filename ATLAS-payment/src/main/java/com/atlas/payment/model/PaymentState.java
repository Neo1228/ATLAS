package com.atlas.payment.model;

public enum PaymentState {
    NOT_STARTED,
    AUTHORIZATION_PENDING,
    AUTHORIZED,
    CAPTURE_PENDING,
    CAPTURED,
    DECLINED,
    UNKNOWN,
    VOID_PENDING,
    VOIDED;

    /** 더 이상 provider 결과로 상태가 바뀌지 않는 최종 상태. 복구 worker와 결제 서비스가 같은 기준을 쓴다. */
    public boolean isTerminal() {
        return this == CAPTURED || this == DECLINED || this == VOIDED;
    }
}
