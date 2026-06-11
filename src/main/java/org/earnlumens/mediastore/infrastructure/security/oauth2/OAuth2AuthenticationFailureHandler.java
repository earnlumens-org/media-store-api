package org.earnlumens.mediastore.infrastructure.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Catches every Spring-Security failure during the OAuth2 dance and redirects
 * the browser back to the SPA with a <em>stable, sanitised</em> error code.
 * <p>
 * The raw exception message is logged server-side only — reflecting it into
 * the redirect URL leaked internal details (exception text, upstream provider
 * errors) into browser history, access logs and Referer headers.
 */
@Component
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationFailureHandler.class);

    /** OAuth2 sub-error codes are lower_snake_case tokens; anything else is dropped. */
    private static final Pattern SAFE_CODE = Pattern.compile("^[a-z0-9_]{1,64}$");

    @Value("${mediastore.frontend.uri}")
    private String frontendBaseUri;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("OAuth2 authentication failure: {} - {}",
                exception.getClass().getSimpleName(), exception.getMessage(), exception);

        String code = classify(exception.getMessage());
        String redirectUrl = frontendBaseUri + "/oauth2/callback?error=" +
                java.net.URLEncoder.encode(code, StandardCharsets.UTF_8);
        response.sendRedirect(redirectUrl);
    }

    /**
     * Maps the bracketed OAuth2 sub-error embedded in the exception message
     * (e.g. {@code [authorization_request_not_found]}) to a short, allow-listed
     * identifier the SPA can branch on. Unknown / unparsable failures collapse
     * to a generic code so no internal detail ever reaches the URL.
     */
    private static String classify(String message) {
        if (message != null) {
            int open = message.indexOf('[');
            int close = message.indexOf(']');
            if (open >= 0 && close > open) {
                String code = message.substring(open + 1, close);
                if (SAFE_CODE.matcher(code).matches()) {
                    return switch (code) {
                        case "authorization_request_not_found" -> "oauth_state_expired";
                        case "access_denied" -> "oauth_user_cancelled";
                        case "invalid_token_response", "invalid_client" -> "oauth_provider_error";
                        default -> code;
                    };
                }
            }
        }
        return "authentication_failed";
    }
}
