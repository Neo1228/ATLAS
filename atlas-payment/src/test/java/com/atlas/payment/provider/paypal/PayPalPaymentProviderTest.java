package com.atlas.payment.provider.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.PaymentOperation;
import com.atlas.payment.dto.request.PaymentRequest;
import java.net.ConnectException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Provider 장애가 UNKNOWN으로 흡수되는 구조에서, 원인을 로그로 남기는지 검증합니다.
 *
 * <p>이 로그가 없으면 운영자는 "PayPal 장애"와 "base URL·자격 증명 오류"를 구분할 수
 * 없습니다. 실제로 로컬 결제 측정이 세 번 실패했을 때 이 정보가 없어 원인 파악에
 * 시간이 걸렸습니다.
 */
class PayPalPaymentProviderTest {
    private final PayPalRestClient client = mock(PayPalRestClient.class);
    private final PayPalPaymentProvider provider = new PayPalPaymentProvider(client, Clock.systemUTC());
    private ListAppender<ILoggingEvent> appender;
    private Logger providerLogger;

    @BeforeEach
    void attachLogCapture() {
        providerLogger = (Logger) LoggerFactory.getLogger(PayPalPaymentProvider.class);
        appender = new ListAppender<>();
        appender.start();
        providerLogger.addAppender(appender);
        providerLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachLogCapture() {
        providerLogger.detachAppender(appender);
    }

    @Test
    void logsStageAndCauseWhenTheProviderIsUnavailable() {
        when(client.createOrder(any(), any(), any(), any(), any(), any()))
                .thenThrow(new PayPalRestClient.PayPalUnavailableException(
                        "PayPal API request failed", new ConnectException("Connection refused")));

        PaymentRequest request = new PaymentRequest(PaymentOperation.AUTHORIZATION,
                UUID.randomUUID(), UUID.randomUUID(), 1000L, "USD", null, null,
                "create-1", "auth-1", "capture-1", "void-1", "return", "cancel");

        assertThatThrownBy(() -> provider.perform(request))
                .isInstanceOf(PaymentException.class)
                .hasFieldOrPropertyWithValue("code", PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE)
                // The cause must survive: it is the only way to tell a network failure
                // from a provider rejection after the service maps this to UNKNOWN.
                .hasCauseInstanceOf(PayPalRestClient.PayPalUnavailableException.class);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getFormattedMessage())
                .contains("stage=perform")
                .contains("failureType=PayPalUnavailableException")
                .contains("causeType=ConnectException");
    }
}
