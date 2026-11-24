package com.atlas.order.exception;

public final class LocationClientException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Failure failure;
    private final String providerCode;

    public LocationClientException(Failure failure, String providerCode, String message) {
        super(message);
        this.failure = failure;
        this.providerCode = providerCode;
    }

    public Failure failure() {
        return failure;
    }

    public String providerCode() {
        return providerCode;
    }

    /**
     * inventory-location 이 돌려주는 업무 코드 중 order 가 판단에 쓰는 것. 이 서비스가 만드는 합성 코드
     * ({@code INVENTORY_HTTP_503}, {@code UNKNOWN_RESERVATION_STATUS} 등)와 알 수 없는 원격 코드가 섞여
     * 저장되는 열린 집합이라 enum 으로 닫지 않고, 판단에 쓰는 값만 이름을 붙인다. 저장되는 값은 원문 그대로다.
     */
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String RESERVATION_CANCELLED = "RESERVATION_CANCELLED";
    public static final String RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
    public static final String INVALID_STATE = "INVALID_STATE";

    /** 원격이 재고 부족으로 거절했는지. */
    public boolean isInsufficientStock() {
        return INSUFFICIENT_STOCK.equals(providerCode);
    }

    /** 원격이 현재 예약 상태에서 허용하지 않는 전이라고 거절했는지. */
    public boolean isInvalidState() {
        return INVALID_STATE.equals(providerCode);
    }

    /** 원격이 되돌릴 수 없는 이유(재고 부족, 예약 취소, 예약 만료)로 확정 거절했는지 코드 문자열로 판단한다. */
    public static boolean isDefinitiveRejectionCode(String code) {
        return INSUFFICIENT_STOCK.equals(code) || RESERVATION_CANCELLED.equals(code)
                || RESERVATION_EXPIRED.equals(code);
    }

    public enum Failure {
        DEFINITIVE_REJECTION,
        RETRYABLE_UNKNOWN,
        PERMANENT
    }
}
