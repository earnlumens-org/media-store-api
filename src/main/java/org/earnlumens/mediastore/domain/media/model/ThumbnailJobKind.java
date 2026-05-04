package org.earnlumens.mediastore.domain.media.model;

/**
 * Kind of image being processed by a thumbnail job.
 *
 * <ul>
 *   <li>{@code THUMBNAIL} — entry thumbnail (grid card)</li>
 *   <li>{@code PREVIEW} — entry preview image (player poster / detail page)</li>
 *   <li>{@code COVER} — collection cover image</li>
 * </ul>
 */
public enum ThumbnailJobKind {
    THUMBNAIL,
    PREVIEW,
    COVER
}
