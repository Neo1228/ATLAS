package com.atlas.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.filter.RequestCorrelationFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

class RequestCorrelationFilterTest {
    @Test
    void generatesServerOwnedRequestIdAndClearsMdcAfterRequest() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "caller-controlled");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY)).isNotEqualTo("caller-controlled");
                assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY)).isNotBlank();
            }
        };

        filter.doFilter(request, response, chain);

        String requestId = response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo(requestId);
        assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY)).isNull();
    }
}
