package com.atlas.application.service.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.controller.settlement.PayPalPayoutOperatorController;
import com.atlas.application.controller.settlement.SellerPayoutProfileController;
import com.atlas.application.security.PayoutRecipientCipher;
import com.atlas.application.service.payout.PayoutDispatchApprovalCommitService;
import com.atlas.application.service.payout.PayoutDispatchApprovalService;
import com.atlas.application.service.payout.PayoutDispatchWorker;
import com.atlas.payment.provider.paypal.payouts.PayPalPayoutsRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 지급 기능 플래그가 꺼진 기본 상태에서는 provider 호출 경로(REST client, worker, 승인 서비스, 운영자 API)가
 * 빈으로 등록되지 않아 PayPal로 나가는 호출이 구조적으로 0건임을 확인한다.
 */
class PayoutsDisabledByDefaultTest {

    private static final Class<?>[] PAYOUT_BEANS = {PayPalPayoutsRestClient.class, PayoutDispatchWorker.class, PayoutDispatchApprovalService.class, PayoutDispatchApprovalCommitService.class, PayoutRecipientCipher.class, SellerPayoutProfileController.class, PayPalPayoutOperatorController.class};

    @Test
    void noPayoutBeanIsRegisteredWhenTheFlagsAreOff() {
        new ApplicationContextRunner().withUserConfiguration(PAYOUT_BEANS).run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : PAYOUT_BEANS) {
                assertThat(context).as(type.getSimpleName()).doesNotHaveBean(type);
            }
        });
    }

    /** 대조: 플래그를 켜면 같은 클래스들이 활성화되어 협력 객체가 없는 이 컨텍스트는 기동에 실패한다. */
    @Test
    void enablingTheFlagActivatesTheBeansSoTheConditionIsActuallyWorking() {
        new ApplicationContextRunner().withUserConfiguration(PAYOUT_BEANS)
                .withPropertyValues("atlas.payment.paypal.payouts.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
