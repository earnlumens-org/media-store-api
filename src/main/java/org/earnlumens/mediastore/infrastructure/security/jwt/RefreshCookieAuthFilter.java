package org.earnlumens.mediastore.infrastructure.security.jwt;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Authenticates requests to /api/media/entitlements/** using the refresh-cookie
 * session (rFTo / rFTo_dev), since {@code <video>}, {@code <audio>} and
 * {@code <img>} elements — and therefore the Cloudflare CDN Worker that proxies
 * them — cannot send Authorization Bearer headers.
 * <p>
 * The filter extracts the refresh-token JWT from the configured cookie,
 * validates it, builds an {@link OAuth2User} principal and stores it in the
 * {@link SecurityContextHolder} so that Spring Security's standard
 * {@code .anyRequest().authenticated()} rule lets the request through.
 * <p>
 * If the cookie is missing or invalid the SecurityContext stays empty and
 * Spring Security will reject the request with 401 via
 * {@link AuthEntryPointJwt}.
 */
public class RefreshCookieAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RefreshCookieAuthFilter.class);

    @Autowired
    private JwtUtils jwtUtils;

    @Value("${mediastore.sec.cookieName}")
    private String cookieName;

    /**
     * Only activate for media-entitlement paths; all other requests
     * continue to use the Bearer-token {@link AuthTokenFilter}.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/media/entitlements/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // If already authenticated (e.g. Bearer token), skip
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String refreshToken = extractRefreshToken(request);
            if (refreshToken != null && jwtUtils.validateJwtToken(refreshToken)) {
                Claims claims = jwtUtils.getAllClaimsFromToken(refreshToken);
                String userId = claims.getSubject();

                Map<String, Object> attributes = new HashMap<>();
                attributes.put("id", userId);
                attributes.put("name", claims.get("name", String.class));
                attributes.put("username", claims.get("username", String.class));
                attributes.put("profile_image_url", claims.get("profile_image_url", String.class));
                attributes.put("oauth_provider", claims.get("oauth_provider", String.class));
                Number followersCount = claims.get("followers_count", Number.class);
                attributes.put("followers_count", followersCount != null ? followersCount.intValue() : 0);

                OAuth2UserAuthority authority = new OAuth2UserAuthority("ROLE_USER", attributes);
                OAuth2User oauth2User = new DefaultOAuth2User(
                        Collections.singletonList(authority),
                        attributes,
                        "id"
                );

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                oauth2User, null, oauth2User.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication from refresh cookie: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String extractRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(c -> cookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
