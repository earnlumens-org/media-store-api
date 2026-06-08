package org.earnlumens.mediastore.web.media;

import org.earnlumens.mediastore.application.media.RatingService;
import org.earnlumens.mediastore.domain.media.dto.response.RatingAggregateResponse;
import org.earnlumens.mediastore.domain.media.dto.response.RatingListResponse;
import org.earnlumens.mediastore.domain.media.dto.response.RatingResponse;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.RatingEntity;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public (no auth) rating read API. Lives under {@code /public/**}
 * (permitAll in {@code WebSecurityConfig}).
 *
 * <ul>
 *   <li>{@code GET /public/ratings/{targetType}/{targetId}} — aggregate + paginated reviews</li>
 *   <li>{@code GET /public/ratings/{targetType}/{targetId}/summary} — aggregate only</li>
 * </ul>
 *
 * <p>{@code targetType} is {@code entry} or {@code collection} (case-insensitive).</p>
 */
@RestController
@RequestMapping("/public/ratings")
public class PublicRatingController {

    private final RatingService ratingService;

    public PublicRatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    @GetMapping("/{targetType}/{targetId}")
    public ResponseEntity<RatingListResponse> getRatings(
            @PathVariable("targetType") String targetTypeRaw,
            @PathVariable("targetId") String targetId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        TargetType targetType = parseTargetType(targetTypeRaw);
        if (targetType == null) {
            return ResponseEntity.badRequest().build();
        }
        String tenantId = TenantContext.require();
        RatingAggregateResponse aggregate = ratingService.getAggregateResponse(tenantId, targetType, targetId);
        Page<RatingEntity> reviews = ratingService.listReviews(tenantId, targetType, targetId, page, size);
        List<RatingResponse> items = reviews.getContent().stream()
                .map(ratingService::toRatingResponse)
                .toList();

        RatingListResponse body = new RatingListResponse(
                aggregate,
                items,
                reviews.getNumber(),
                reviews.getSize(),
                reviews.getTotalElements(),
                reviews.hasNext()
        );
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{targetType}/{targetId}/summary")
    public ResponseEntity<RatingAggregateResponse> getSummary(
            @PathVariable("targetType") String targetTypeRaw,
            @PathVariable("targetId") String targetId) {
        TargetType targetType = parseTargetType(targetTypeRaw);
        if (targetType == null) {
            return ResponseEntity.badRequest().build();
        }
        String tenantId = TenantContext.require();
        return ResponseEntity.ok(ratingService.getAggregateResponse(tenantId, targetType, targetId));
    }

    private TargetType parseTargetType(String raw) {
        if (raw == null) return null;
        try {
            return TargetType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
