package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.PublicSearchService;
import org.earnlumens.mediastore.domain.media.dto.response.SearchResultsResponse;
import org.earnlumens.mediastore.domain.media.dto.response.SearchSuggestionsResponse;
import org.earnlumens.mediastore.infrastructure.security.AnonymousSearchBudget;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (no auth) search controller — channels (creators) + content for the
 * resolved tenant, the way large platforms present results.
 *
 * <p>Path is under {@code /public/**} which is {@code permitAll} in
 * {@link org.earnlumens.mediastore.infrastructure.security.WebSecurityConfig}.
 * Abuse is mitigated in three layers:
 * <ol>
 *   <li>{@link org.earnlumens.mediastore.infrastructure.security.RateLimitFilter}
 *       — hard, IP-based DoS protection (the {@code SEARCH} tier).</li>
 *   <li>{@link AnonymousSearchBudget} — a soft "sign in to keep searching" gate
 *       for anonymous visitors after a generous number of free searches.</li>
 *   <li>{@link PublicSearchService} — bounded queries (length/page/size caps).</li>
 * </ol>
 */
@RestController
@RequestMapping("/public/search")
public class PublicSearchController {

    private final PublicSearchService publicSearchService;
    private final AnonymousSearchBudget anonymousSearchBudget;

    public PublicSearchController(PublicSearchService publicSearchService,
                                  AnonymousSearchBudget anonymousSearchBudget) {
        this.publicSearchService = publicSearchService;
        this.anonymousSearchBudget = anonymousSearchBudget;
    }

    /**
     * GET /public/search?q=&type=&sort=relevance&page=0&size=24
     * Unified results: channels (page 0 only) + paginated content.
     */
    @GetMapping
    public ResponseEntity<SearchResultsResponse> search(
            @RequestParam(value = "q") String query,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "sort", defaultValue = "relevance") String sort,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "24") int size,
            HttpServletRequest request
    ) {
        String tenantId = TenantContext.require();

        // Anonymous visitors get a generous free budget; once exhausted we ask
        // them to sign in instead of serving more DB-backed searches. Paging
        // through results the visitor already loaded (page > 0) is not charged
        // against the budget so we don't interrupt mid-scroll.
        boolean authenticated = isAuthenticated();
        if (!authenticated && page == 0 && !anonymousSearchBudget.tryConsume(request)) {
            return ResponseEntity.ok(SearchResultsResponse.loginRequired());
        }

        SearchResultsResponse response = publicSearchService.search(tenantId, query, type, sort, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /public/search/suggestions?q=
     * Lightweight autocomplete completions. Not charged against the anonymous
     * budget (these fire on keystrokes); the SEARCH rate-limit tier still caps
     * the per-IP request rate.
     */
    @GetMapping("/suggestions")
    public ResponseEntity<SearchSuggestionsResponse> suggestions(
            @RequestParam(value = "q") String query
    ) {
        String tenantId = TenantContext.require();
        return ResponseEntity.ok(publicSearchService.suggestions(tenantId, query));
    }

    private boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof OAuth2User;
    }
}
