package org.earnlumens.mediastore.infrastructure.security;

import org.earnlumens.mediastore.infrastructure.security.jwt.AuthEntryPointJwt;
import org.earnlumens.mediastore.infrastructure.security.jwt.AuthTokenFilter;
import org.earnlumens.mediastore.infrastructure.security.oauth2.OAuth2AuthenticationFailureHandler;
import org.earnlumens.mediastore.infrastructure.security.oauth2.OAuth2AuthenticationSuccessHandler;
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
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class WebSecurityConfig {

    @Value("${mediastore.frontend.uri}")
    private String mainDomain;

    private final AuthEntryPointJwt authEntryPointJwt;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    public WebSecurityConfig(
            AuthEntryPointJwt authEntryPointJwt,
            OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler,
            OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler
    ) {
        this.authEntryPointJwt = authEntryPointJwt;
        this.oAuth2AuthenticationFailureHandler = oAuth2AuthenticationFailureHandler;
        this.oAuth2AuthenticationSuccessHandler = oAuth2AuthenticationSuccessHandler;
    }

    @Bean
    public AuthTokenFilter authenticationJwtTokenFilter() {
        return new AuthTokenFilter();
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
                                "/api/waitlist/**",
                                "/api/auth/session",
                                "/api/auth/refresh",
                                "/api/user/by-username/**",
                                "/api/user/exists/**",
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
                .addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                "/oauth2/authorization"
        );
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
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
        configuration.setAllowedOrigins(Arrays.asList(mainDomain, "http://localhost:3000"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
