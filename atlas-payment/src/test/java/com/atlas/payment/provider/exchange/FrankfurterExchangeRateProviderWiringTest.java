package com.atlas.payment.provider.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.ConversionService;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.web.client.RestClient;

/**
 * 환율 공급자 설정값에 따라 실제 컴포넌트 선언 그대로 캐시 래퍼가 올바른 공급자를 감싸는지 확인한다.
 *
 * <p>캐시 래퍼가 BOK ECOS 구현 클래스에 직접 의존하던 때는 {@code provider=frankfurter}에서
 * 컨텍스트가 뜨지 않았다. 이 시험은 컴포넌트 선언(조건, 한정자)을 그대로 쓴다.
 */
class FrankfurterExchangeRateProviderWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ExchangeRateConfiguration.class);

    @Test
    void frankfurterProviderIsWrappedByTheSharedCache() {
        contextRunner.withPropertyValues("atlas.payment.exchange-rate.provider=frankfurter").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FrankfurterExchangeRateProvider.class);
            assertThat(context).doesNotHaveBean(BokEcosExchangeRateProvider.class);
            assertThat(context.getBean(ExchangeRateProvider.class)).isInstanceOf(CachingExchangeRateProvider.class);
        });
    }

    @Test
    void bokEcosProviderRemainsTheDefaultDelegateOfTheSharedCache() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BokEcosExchangeRateProvider.class);
            assertThat(context).doesNotHaveBean(FrankfurterExchangeRateProvider.class);
            assertThat(context.getBean(ExchangeRateProvider.class)).isInstanceOf(CachingExchangeRateProvider.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({BokEcosExchangeRateProvider.class, FrankfurterExchangeRateProvider.class,
            CachingExchangeRateProvider.class})
    static class ExchangeRateConfiguration {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean(name = "conversionService")
        ConversionService conversionService() {
            DefaultFormattingConversionService conversionService = new DefaultFormattingConversionService();
            conversionService.addConverter(String.class, Duration.class, Duration::parse);
            return conversionService;
        }
    }
}
