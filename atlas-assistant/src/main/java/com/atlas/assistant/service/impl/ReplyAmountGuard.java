package com.atlas.assistant.service.impl;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 모델 응답 문장 속 금액과 링크가 서버 값과 같은지 확인한다(ADR-0109). 하나라도 다르거나 해석할 수 없으면 응답 전체를
 * 서버 문장으로 바꾼다. 모델이 가격을 지어내거나, 상품 설명에 심은 주입 문구가 가짜 금액·결제 링크를 말하게 해도
 * 구매자에게 가지 않게 하려는 것이다.
 *
 * <ul>
 *   <li>금액: "39,000원", "39000 KRW", "₩39,000", "3만 9천원"처럼 통화 표시가 붙은 수. 허용 집합은 이 대화의 도구 결과에
 *       나온 {@code *_won} 값이다.</li>
 *   <li>달러 표기($, USD)는 서버가 원화로만 계산하므로 항상 불일치로 본다.</li>
 *   <li>링크: http(s) 주소는 서버가 이번 차례에 발급한 PayPal 승인 링크만 허용한다.</li>
 * </ul>
 */
final class ReplyAmountGuard {
    private static final Pattern WON = Pattern.compile(
            "(?:₩|KRW)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)"
                    + "|([0-9][0-9,.]*(?:만\\s*(?:[0-9][0-9,.]*천\\s*)?(?:[0-9][0-9,.]*)?|천\\s*(?:[0-9][0-9,.]*)?)?)"
                    + "\\s*(?:원|KRW|won\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KOREAN_UNITS = Pattern.compile("^(?:([0-9]+)만)?(?:([0-9]+)천)?([0-9]+)?$");
    private static final Pattern DOLLAR = Pattern.compile("\\$\\s*[0-9]|[0-9]\\s*(?:USD|달러)", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]>\"']+", Pattern.CASE_INSENSITIVE);

    private ReplyAmountGuard() {
    }

    static boolean matchesServer(String reply, Set<Long> serverAmounts, String approvalUrl) {
        if (reply == null) return true;
        if (DOLLAR.matcher(reply).find()) return false;
        Matcher url = URL.matcher(reply);
        while (url.find()) {
            if (approvalUrl == null || !approvalUrl.equals(url.group())) return false;
        }
        Matcher amount = WON.matcher(reply);
        while (amount.find()) {
            String raw = amount.group(1) != null ? amount.group(1) : amount.group(2);
            Long value = parse(raw);
            if (value == null || !serverAmounts.contains(value)) return false;
        }
        return true;
    }

    /** 숫자와 만·천 단위를 원 단위 정수로 바꾼다. 소수점이 있거나 형식이 어긋나면 null(불일치로 처리). */
    static Long parse(String raw) {
        String compact = raw.replace(",", "").replaceAll("\\s+", "");
        if (compact.contains(".")) return null;
        Matcher units = KOREAN_UNITS.matcher(compact);
        if (!units.matches() || compact.isEmpty()) return null;
        try {
            long value = 0;
            if (units.group(1) != null) value = Math.addExact(value, Math.multiplyExact(Long.parseLong(units.group(1)), 10_000L));
            if (units.group(2) != null) value = Math.addExact(value, Math.multiplyExact(Long.parseLong(units.group(2)), 1_000L));
            if (units.group(3) != null) value = Math.addExact(value, Long.parseLong(units.group(3)));
            return value;
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }
}
