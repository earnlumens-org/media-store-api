package org.earnlumens.mediastore.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * Temporary diagnostic filter to log request details for 403 responses.
 * Runs before all other filters to capture the full picture.
 *
 * TODO: Remove once the mobile 403 issue is resolved.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE - 1)
public class DiagnosticFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        ContentCachingResponseWrapper wrappedResponse =
                new ContentCachingResponseWrapper(response);

        filterChain.doFilter(request, wrappedResponse);

        int status = wrappedResponse.getStatus();

        if (status == 403) {
            String method = request.getMethod();
            String path = request.getRequestURI();
            String query = request.getQueryString();
            String origin = request.getHeader("Origin");
            String referer = request.getHeader("Referer");
            String userAgent = request.getHeader("User-Agent");
            String host = request.getHeader("Host");
            String cfIp = request.getHeader("CF-Connecting-IP");
            String xff = request.getHeader("X-Forwarded-For");
            String secFetchSite = request.getHeader("Sec-Fetch-Site");
            String secFetchMode = request.getHeader("Sec-Fetch-Mode");

            boolean hasCookies = request.getCookies() != null && request.getCookies().length > 0;
            String cookieNames = "none";
            if (hasCookies) {
                StringBuilder sb = new StringBuilder();
                for (Cookie c : request.getCookies()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(c.getName());
                }
                cookieNames = sb.toString();
            }

            // Read response body snippet (e.g. CORS error or Spring Security error)
            byte[] body = wrappedResponse.getContentAsByteArray();
            String bodySnippet = body.length > 0
                    ? new String(body, 0, Math.min(body.length, 500))
                    : "(empty)";

            logger.warn("DIAG-403 | {} {} {} | status=403 | origin={} | referer={} | host={} | "
                            + "ua={} | ip={} | xff={} | sec-fetch-site={} | sec-fetch-mode={} | "
                            + "cookies=[{}] | response-body={}",
                    method,
                    path,
                    query != null ? "?" + query : "",
                    origin,
                    referer,
                    host,
                    userAgent,
                    cfIp,
                    xff,
                    secFetchSite,
                    secFetchMode,
                    cookieNames,
                    bodySnippet);
        }

        wrappedResponse.copyBodyToResponse();
    }
}
