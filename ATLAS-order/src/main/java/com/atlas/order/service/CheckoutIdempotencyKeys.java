package com.atlas.order.service;

/**
 * checkout Idempotency-Key 상한.
 *
 * <p>같은 checkout 리소스인데 생성은 256자, reconcile·cancel 은 221자를 받던 비대칭이 있었다.
 * 222~256자 키로 checkout 을 만들면 성공하지만, 이후 같은 checkout 의 결제 상태 확인이나
 * 취소가 키 길이 때문에 거절되어 복구 경로가 막혔다.
 *
 * <p>221자를 기준으로 맞춘다. 이 값은 ADR-0015 가 정한 것이며, 내부 suffix({@code :void} 등)를
 * 붙여도 {@code VARCHAR(255)} 컬럼에 들어가도록 34자를 남긴 값이다. 늘리는 방향(256)이 아니라
 * 이 값에 맞추는 이유는 reconcile·cancel·payment 경로가 이미 221자에 맞춰져 있기 때문이다.
 */
public final class CheckoutIdempotencyKeys {
    /** checkout 생성·reconcile·cancel 이 공유하는 상한. */
    public static final int MAX_LENGTH = 221;

    private CheckoutIdempotencyKeys() {
    }

    public static boolean isTooLong(String idempotencyKey) {
        return idempotencyKey != null && idempotencyKey.length() > MAX_LENGTH;
    }
}
