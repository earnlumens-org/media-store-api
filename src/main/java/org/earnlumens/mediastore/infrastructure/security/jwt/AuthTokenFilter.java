package org.earnlumens.mediastore.infrastructure.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class AuthTokenFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils;

    private static final Logger logger = LoggerFactory.getLogger(AuthTokenFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String jwt = parseJwt(request);
            if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
                Claims claims = Jwts.parser()
                        .verifyWith(jwtUtils.key())
                        .build()
                        .parseSignedClaims(jwt)
                        .getPayload();

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

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        oauth2User,
                        null,
                        oauth2User.getAuthorities()
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        return null;
    }
}
