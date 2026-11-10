package com.atlas.payment.persistence.state;

/** Fake PSP 처리 결과를 로컬에 저장할 때 사용하는 상태. */
public enum FakePspResult { AUTHORIZED, CAPTURED, DECLINED, PENDING, UNKNOWN, VOIDED }
