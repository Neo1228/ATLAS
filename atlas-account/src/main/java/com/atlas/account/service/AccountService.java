package com.atlas.account.service;

import com.atlas.account.dto.request.LoginRequest;
import com.atlas.account.dto.request.MfaChallengeRequest;
import com.atlas.account.dto.request.RefreshTokenRequest;
import com.atlas.account.dto.request.RegisterBuyerRequest;
import com.atlas.account.dto.response.AccountTokenResponse;
import com.atlas.account.dto.response.AccountActionResponse;
import com.atlas.account.dto.response.AccountLoginResponse;
import com.atlas.account.dto.response.MfaEnrollmentResponse;
import com.atlas.account.dto.response.MfaRecoveryCodesResponse;
import com.atlas.account.dto.response.AccountSessionPage;
import com.atlas.account.dto.response.VerifiedSellerAccountResponse;
import com.atlas.account.dto.response.AccountSuspensionActionResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountService {
    AccountActionResponse registerBuyer(RegisterBuyerRequest request, String origin);

    /**
     * 메일 발송형 요청. 한도를 넘으면 메일을 보내지 않지만 응답은 동일하게 유지한다.
     * 응답을 바꾸면 계정 존재 여부가 드러나므로, 남은 한도 없음도 성공처럼 보여야 한다.
     */
    AccountActionResponse requestPasswordReset(String requestedEmail, String origin);

    AccountActionResponse requestEmailVerification(String requestedEmail, String origin);

    void verifyEmail(String presentedToken);

    /**
     * 비밀번호를 재설정한다. 재설정 토큰은 메일함 소유를 증명하므로, 성공하면 잠금 상태였을 수 있는
     * 로그인 실패 카운터도 함께 비운다. 그러지 않으면 비밀번호를 되찾은 사용자가 남은 창(기본 15분)
     * 동안 새 비밀번호로도 로그인하지 못한다. MFA 카운터는 메일함만으로 풀 수 없으므로 건드리지 않는다.
     *
     * <p>카운터 삭제는 독립 transaction이라 재설정 transaction이 끝난 뒤에 한다. 이미 커밋된 재설정은
     * 토큰이 소비돼 다시 시도할 수 없으므로, 삭제가 실패해도 오류로 돌려주지 않고 창이 지나 풀리게 둔다.
     */
    void resetPassword(String presentedToken, String newPassword);

    /**
     * @param origin 클라이언트 주소. 없으면 출처 축은 "unknown" 하나로 합산된다.
     */
    AccountLoginResponse login(LoginRequest request, String origin);

    MfaEnrollmentResponse startMfaEnrollment(UUID accountId);

    MfaRecoveryCodesResponse confirmMfaEnrollment(UUID accountId, String code);

    void disableMfa(UUID accountId, String code);

    AccountTokenResponse completeMfaLogin(MfaChallengeRequest request);

    AccountTokenResponse refresh(RefreshTokenRequest request);

    void logout(UUID accountId, UUID accessFamilyId, String refreshToken);

    /**
     * 본인이 자기 계정을 폐쇄합니다.
     *
     * <p>폐쇄는 되돌릴 수 없으므로 비밀번호를 다시 확인합니다. 액세스 토큰만으로
     * 허용하면 탈취된 기기에서 악의적으로 계정을 닫을 수 있습니다.
     *
     * <p>한 트랜잭션에서 상태 변경과 모든 세션 폐기를 함께 수행합니다:
     * <ul>
     *   <li>{@code user_account.status = 'DISABLED'} — 이후 모든 검증이 거부됩니다.</li>
     *   <li>모든 refresh family 폐기 — 갱신 경로를 닫습니다.</li>
     *   <li>진행 중 MFA challenge 폐기 — 대기 중 인증을 무효화합니다.</li>
     * </ul>
     *
     * <p>응답 직후 같은 액세스 토큰이 401 이 되는 것은 검증기가 {@code status='ACTIVE'} 를
     * 요구하기 때문이며, 이 계약을 통합 테스트로 고정했습니다.
     *
     * <p>멱등성: 이미 폐쇄된 계정에 다시 요청하면 비밀번호가 맞아도 상태 변경 없이
     * 조용히 종료합니다(204). 호출자가 재시도해도 안전합니다.
     */
    void closeAccount(UUID accountId, String password);

    /**
     * 운영자가 계정을 정지시킵니다.
     *
     * <p>본인 폐쇄와 달리 해제될 수 있고, 근거와 책임을 남겨야 합니다. 그래서
     * actor·reason·idempotency-key 를 모두 요구하고 감사 기록을 함께 씁니다.
     *
     * <p>정지하면 상태가 DISABLED 가 되어, 모든 로그인·갱신·액세스 검증이
     * 즉시 거부됩니다({@code isRefreshFamilyActive} 가 status='ACTIVE' 를 요구).
     * 진행 중 세션을 즉시 끊기 위해 refresh family 와 MFA challenge 도 폐기합니다.
     *
     * <p>멱등: 같은 idempotency-key 로 다시 요청하면 아무 것도 바꾸지 않고 종료합니다.
     * 운영 자동화가 재시도해도 감사 기록이 중복되지 않습니다.
     */
    void suspendAccount(UUID accountId, String reason, String actor, String idempotencyKey);

    /** 운영자가 계정 정지를 해제합니다. 감사 기록을 남기고 세션을 새로 발급받게 합니다. */
    void releaseAccount(UUID accountId, String reason, String actor, String idempotencyKey);

    /** 한 계정의 정지·해제 감사 기록입니다. 운영 확인용입니다. */
    List<AccountSuspensionActionResponse> suspensionHistory(UUID accountId, int limit);

    void linkSeller(UUID accountId, UUID sellerId);

    boolean ownsBuyer(UUID accountId, String buyerRef);

    boolean ownsSeller(UUID accountId, UUID sellerId);

    /**
     * Seller와 연결된 active account 중 이메일 검증이 완료된 identity를 조회한다.
     * 호출 account가 해당 Seller를 소유하지 않으면 조회를 거부한다.
     */
    Optional<VerifiedSellerAccountResponse> findVerifiedSellerAccount(UUID accountId, UUID sellerId);

    /**
     * 요청 본문이 가리킨 Seller를 호출 account가 소유하는지 확인한다. 경로 변수로 알 수 있는 소유권은
     * {@code AccountOwnershipInterceptor}가 검사하고, 본문에만 있는 값은 이 메서드로 검사한다.
     */
    void requireSellerOwnership(UUID accountId, UUID sellerId);

    /**
     * 호출 account의 Buyer 식별자를 돌려주되, 요청 본문이 다른 Buyer를 가리키면 거부한다.
     */
    String requireOwnBuyerRef(UUID accountId, String requestedBuyerRef);

    String buyerRef(UUID accountId);

    boolean isAccessTokenFamilyActive(UUID accountId, UUID familyId);

    AccountSessionPage listSessions( UUID accountId, UUID currentFamilyId, int requestedLimit, int offset);

    void revokeSession(UUID accountId, UUID familyId);
}
