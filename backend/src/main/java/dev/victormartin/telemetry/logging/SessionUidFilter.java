package dev.victormartin.telemetry.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(0)
public class SessionUidFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Session-Uid";
    private static final String PARAM = "sessionUid";
    private static final String MDC_KEY = "sessionUid";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uid = request.getHeader(HEADER);
        if (uid == null || uid.isBlank()) {
            uid = request.getParameter(PARAM);
        }
        boolean set = uid != null && !uid.isBlank();
        if (set) {
            MDC.put(MDC_KEY, uid);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (set) {
                MDC.remove(MDC_KEY);
            }
        }
    }
}
