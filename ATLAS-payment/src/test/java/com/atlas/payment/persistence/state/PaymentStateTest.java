package com.atlas.payment.persistence.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentStateTest {

    @Test
    void onlyProviderSettledStatesAreTerminal() {
        Set<PaymentState> terminal = EnumSet.of(PaymentState.CAPTURED, PaymentState.DECLINED, PaymentState.VOIDED);

        for (PaymentState state : PaymentState.values()) {
            assertThat(state.isTerminal())
                    .as("%s terminal", state)
                    .isEqualTo(terminal.contains(state));
        }
    }

    @Test
    void unresolvedStatesStayRecoverable() {
        assertThat(PaymentState.UNKNOWN.isTerminal()).isFalse();
        assertThat(PaymentState.AUTHORIZATION_PENDING.isTerminal()).isFalse();
        assertThat(PaymentState.CAPTURE_PENDING.isTerminal()).isFalse();
        assertThat(PaymentState.VOID_PENDING.isTerminal()).isFalse();
    }
}
