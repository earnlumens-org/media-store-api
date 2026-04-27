package org.earnlumens.mediastore.infrastructure.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rewrites the request so that downstream code sees the visitor's original
 * host, not the upstream Cloud Run hostname.
 * <p>
 * Spring Boot's {@code server.forward-headers-strategy=framework} registers
 * {@link org.springframework.web.filter.ForwardedHeaderFilter}, but in this
 * deployment Cloud Run mutates the {@code X-Forwarded-Host} header before
 * Spring sees it (Cloud Run rewrites X-Forwarded-* headers based on the
 * inbound {@code Host}, and Cloudflare Workers cannot override the outbound
 * {@code Host} on a {@code fetch()} call). The result was OAuth2
 * {@code redirect_uri} values and {@link TenantResolver} both anchoring on
 * the {@code *.run.app} hostname instead of the visitor's tenant subdomain.
 * <p>
 * This filter sidesteps the problem by reading a non-standard
 * {@code X-Visitor-Host} / {@code X-Visitor-Proto} pair that the edge Worker
 * sets explicitly. Cloud Run does not touch these custom headers, so they
 * arrive at Spring intact. The request is then wrapped so that
 * {@link HttpServletRequest#getServerName()},
 * {@link HttpServletRequest#getServerPort()},
 * {@link HttpServletRequest#getScheme()},
 * {@link HttpServletRequest#isSecure()},
 * {@link HttpServletRequest#getRequestURL()} and the standard
 * {@code X-Forwarded-Host} / {@code X-Forwarded-Proto} headers all reflect
 * the visitor's origin.
 * <p>
 * Runs at {@link Ordered#HIGHEST_PRECEDENCE} so the wrapped request is the
 * one every other filter (including {@link TenantFilter}) and Spring Security
 * handler sees.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VisitorHostFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(VisitorHostFilter.class);

    /** Header set by the edge Worker. Custom name avoids Cloud Run's X-Forwarded-* rewrite. */
    public static final String VISITOR_HOST_HEADER = "X-Visitor-Host";
    public static final String VISITOR_PROTO_HEADER = "X-Visitor-Proto";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String visitorHost = request.getHeader(VISITOR_HOST_HEADER);
        String visitorProto = request.getHeader(VISITOR_PROTO_HEADER);

        if (visitorHost == null || visitorHost.isBlank()) {
            // No visitor header — request didn't come through our edge Worker
            // (e.g. health probe from Cloud Run's frontend). Pass through.
            filterChain.doFilter(request, response);
            return;
        }

        VisitorHostRequestWrapper wrapped = new VisitorHostRequestWrapper(request, visitorHost, visitorProto);
        if (logger.isTraceEnabled()) {
            logger.trace("VisitorHost rewrite: {} -> {}", request.getServerName(), wrapped.getServerName());
        }
        filterChain.doFilter(wrapped, response);
    }

    /**
     * Returns the visitor's host (and synthesizes scheme/port/URL) from the
     * X-Visitor-* headers while still answering as a normal HttpServletRequest.
     * Also exposes the standard X-Forwarded-Host / X-Forwarded-Proto values so
     * any downstream code that prefers those keeps working.
     */
    private static final class VisitorHostRequestWrapper extends HttpServletRequestWrapper {
        private final String host;
        private final int port;
        private final String scheme;

        VisitorHostRequestWrapper(HttpServletRequest delegate, String visitorHost, String visitorProto) {
            super(delegate);
            String hostHeader = visitorHost.trim();
            // Strip an explicit port if present (e.g. "host:8443").
            int colon = hostHeader.indexOf(':');
            if (colon > 0) {
                this.host = hostHeader.substring(0, colon);
                int parsed;
                try {
                    parsed = Integer.parseInt(hostHeader.substring(colon + 1));
                } catch (NumberFormatException e) {
                    parsed = -1;
                }
                this.port = parsed;
            } else {
                this.host = hostHeader;
                this.port = -1; // Resolve later from scheme.
            }
            this.scheme = (visitorProto != null && !visitorProto.isBlank())
                    ? visitorProto.trim().toLowerCase()
                    : "https";
        }

        @Override
        public String getServerName() {
            return host;
        }

        @Override
        public int getServerPort() {
            if (port > 0) return port;
            return "https".equals(scheme) ? 443 : 80;
        }

        @Override
        public String getScheme() {
            return scheme;
        }

        @Override
        public boolean isSecure() {
            return "https".equals(scheme);
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            url.append(scheme).append("://").append(host);
            int p = getServerPort();
            boolean defaultPort = ("https".equals(scheme) && p == 443) || ("http".equals(scheme) && p == 80);
            if (!defaultPort) {
                url.append(':').append(p);
            }
            url.append(getRequestURI());
            return url;
        }

        @Override
        public String getHeader(String name) {
            // Project the visitor headers onto the standard ones so any code
            // path that reads X-Forwarded-Host / X-Forwarded-Proto sees the
            // right values too.
            if ("X-Forwarded-Host".equalsIgnoreCase(name) || "Host".equalsIgnoreCase(name)) {
                return host + (port > 0 ? ":" + port : "");
            }
            if ("X-Forwarded-Proto".equalsIgnoreCase(name)) {
                return scheme;
            }
            return super.getHeader(name);
        }
    }
}
