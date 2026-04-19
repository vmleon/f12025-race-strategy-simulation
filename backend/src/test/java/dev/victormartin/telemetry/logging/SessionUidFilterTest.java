package dev.victormartin.telemetry.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionUidFilterTest {

    @Test
    void setsMdcFromSessionUidParameter() throws ServletException, IOException {
        SessionUidFilter filter = new SessionUidFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/any");
        req.setParameter("sessionUid", "42");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (request, response) -> {
            assertThat(MDC.get("sessionUid")).isEqualTo("42");
        };

        filter.doFilter(req, res, chain);
        assertThat(MDC.get("sessionUid")).isNull();
    }

    @Test
    void setsMdcFromSessionUidHeader() throws ServletException, IOException {
        SessionUidFilter filter = new SessionUidFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/any");
        req.addHeader("X-Session-Uid", "99");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (request, response) ->
            assertThat(MDC.get("sessionUid")).isEqualTo("99");

        filter.doFilter(req, res, chain);
        assertThat(MDC.get("sessionUid")).isNull();
    }

    @Test
    void doesNotSetMdcWhenAbsent() throws ServletException, IOException {
        SessionUidFilter filter = new SessionUidFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/any");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (request, response) ->
            assertThat(MDC.get("sessionUid")).isNull();

        filter.doFilter(req, res, chain);
        assertThat(MDC.get("sessionUid")).isNull();
    }
}
