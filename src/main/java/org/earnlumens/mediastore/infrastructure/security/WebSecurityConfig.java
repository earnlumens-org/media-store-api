package org.earnlumens.mediastore.infrastructure.security;

import org.earnlumens.mediastore.infrastructure.security.jwt.AuthEntryPointJwt;
import org.earnlumens.mediastore.infrastructure.security.jwt.AuthTokenFilter;
import org.earnlumens.mediastore.infrastructure.security.jwt.RefreshCookieAuthFilter;
import org.earnlumens.mediastore.infrastructure.security.oauth2.OAuth2AuthenticationFailureHandler;
import org.earnlumens.mediastore.infrastructure.security.oauth2.OAuth2AuthenticationSuccessHandler;
import org.earnlumens.mediastore.infrastructure.security.oauth2.TenantOAuth2AuthorizationRequestResolver;
import org.earnlumens.mediastore.infrastructure.tenant.TenantFilter;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class WebSecurityConfig {

    @Value("${mediastore.frontend.uri}")
    private String mainDomain;

    /**
     * Comma-separated allow-list of CORS origins. When empty, only
     * {@code mediastore.frontend.uri} is allowed. Set
     * {@code MEDIASTORE_CORS_ALLOWED_ORIGINS} in dev to add
     * {@code http://localhost:3000} etc.; never include dev origins
     * in the prod env var (CORS + AllowCredentials=true on a wildcarded
     * origin set lets a compromised dev host steal session cookies).
     */
    @Value("${mediastore.cors.allowed-origins:}")
    private String allowedOriginsConfig;

    private final AuthEntryPointJwt authEntryPointJwt;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final TenantFilter tenantFilter;
    private final RateLimitFilter rateLimitFilter;

    public WebSecurityConfig(
            AuthEntryPointJwt authEntryPointJwt,
            OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler,
            OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler,
            TenantFilter tenantFilter,
            RateLimitFilter rateLimitFilter
    ) {
        this.authEntryPointJwt = authEntryPointJwt;
        this.oAuth2AuthenticationFailureHandler = oAuth2AuthenticationFailureHandler;
        this.oAuth2AuthenticationSuccessHandler = oAuth2AuthenticationSuccessHandler;
        this.tenantFilter = tenantFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public AuthTokenFilter authenticationJwtTokenFilter() {
        return new AuthTokenFilter();
    }

    @Bean
    public RefreshCookieAuthFilter refreshCookieAuthFilter() {
        return new RefreshCookieAuthFilter();
    }

    @Bean
    SecurityFilterChain springSecurity(
            HttpSecurity http,
            OAuth2AuthorizationRequestResolver resolver,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource,
            DefaultOAuth2UserService oauth2UserService
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authEntryPointJwt))
                .authorizeHttpRequests(r -> r
                        .requestMatchers(
                                "/public/**",
                                "/api/internal/**",
                                "/api/waitlist/**",
                                "/api/mock/**",
                                "/api/auth/session",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/user/by-username/**",
                                "/api/user/exists/**",
                                "/api/media/entitlements/**",
                                "/oauth2/authorization/**",
                                "/login/oauth2/**",
                                "/error"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(l -> l
                        .authorizationEndpoint(a -> a.authorizationRequestResolver(resolver))
                    .userInfoEndpoint(u -> u.userService(oauth2UserService))
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                        .failureHandler(oAuth2AuthenticationFailureHandler)
                )
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(refreshCookieAuthFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    OAuth2AuthorizationRequestResolver pkceResolver(
            ClientRegistrationRepository clientRegistrationRepository,
            TenantConfigService tenantConfigService
    ) {
        DefaultOAuth2AuthorizationRequestResolver delegate = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                "/oauth2/authorization"
        );
        delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        // Wrap with tenant-aware resolver so the SuccessHandler can redirect
        // the user back to the subdomain they started the OAuth flow from
        // (the entire OAuth handshake itself runs on apex because that is the
        // only redirect_uri registered with X / Google / Apple).
        return new TenantOAuth2AuthorizationRequestResolver(delegate, tenantConfigService);
    }

    @Bean
    @SuppressWarnings("unchecked")
    DefaultOAuth2UserService oauth2UserService() {
        DefaultOAuth2UserService service = new DefaultOAuth2UserService();
        service.setAttributesConverter(request -> attributes -> {
            String registrationId = request.getClientRegistration().getRegistrationId();
            return switch (registrationId) {
                case "x" -> {
                    Object data = attributes.get("data");
                    if (data instanceof Map) {
                        Map<String, Object> original = (Map<String, Object>) data;
                        Map<String, Object> hMap = new HashMap<>(original);
                        hMap.put("oauth_provider", registrationId);
                        yield hMap;
                    }
                    Map<String, Object> hMap = new HashMap<>(attributes);
                    hMap.put("oauth_provider", registrationId);
                    yield hMap;
                }
                default -> {
                    Map<String, Object> hMap = new HashMap<>(attributes);
                    hMap.put("oauth_provider", registrationId);
                    yield hMap;
                }
            };
        });
        return service;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins;
        if (allowedOriginsConfig == null || allowedOriginsConfig.isBlank()) {
            origins = List.of(mainDomain);
        } else {
            origins = Arrays.stream(allowedOriginsConfig.split(","))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        // Defence-in-depth: never accept a wildcard with AllowCredentials=true.
        if (origins.stream().anyMatch(o -> o.equals("*") || o.contains("*"))) {
            throw new IllegalStateException(
                    "mediastore.cors.allowed-origins must not contain wildcards "
                  + "(AllowCredentials=true). Got: " + origins);
        }
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
