package org.earnlumens.mediastore.domain.user.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public class User {

    private String id;
    private String oauthProvider;
    private String oauthUserId;
    private String username;
    private String displayName;
    private String profileImageUrl;
    private Integer followersCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
    private String defaultWalletId;

    // ── Sanction / ban model (see docs/MODERATION-USER-SANCTIONS.md) ──
    // The {@code blocked} flag is the canonical "is this user currently barred?"
    // signal — every login / refresh path MUST consult it. The remaining fields
    // describe the WHY and FOR HOW LONG so we can render an honest status to the
    // affected user and so an expired temp-ban auto-lifts on next login.
    private boolean blocked;
    private LocalDateTime blockedAt;
    private String blockedByRequestId;
    /** Human-readable reason shown to the affected user. */
    private String banReason;
    /** {@code null} = permanent. When non-null and in the past, login auto-unbans. */
    private LocalDateTime banExpiresAt;
    /** OauthUserId of the moderator who issued the ban. */
    private String banIssuedBy;
    /** WARNING | STRIKE_1 | STRIKE_2 | STRIKE_3 | TEMP_BAN | PERMA_BAN */
    private String banType;
    /** Cumulative strike count. Strikes "expire" only via explicit moderator action. */
    private Integer strikeCount;
    private LocalDateTime lastStrikeAt;

    private String tempUUID;
    private Instant tempUUIDCreatedAt;

    /**
     * Consumer-side content language preferences.
     * <p>
     * {@code contentLanguages} — ISO 639-1 codes the user wants to see in
     * public feeds (e.g. {@code ["en", "es"]}). Empty/null means "no
     * filter" and behaves like {@code showAllLanguages = true}.
     * <p>
     * {@code includeMulti} — when {@code true}, language-free content
     * tagged {@code "multi"} (instrumental music, images, mixed-language)
     * is included in addition to {@code contentLanguages}. Default true.
     * <p>
     * {@code showAllLanguages} — escape hatch for discovery mode. When
     * {@code true}, {@code contentLanguages} and {@code includeMulti} are
     * ignored and the feed returns content in any language. Default false.
     */
    private List<String> contentLanguages;
    private Boolean includeMulti;
    private Boolean showAllLanguages;

    public User() {
        this.blocked = false;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOauthProvider() {
        return oauthProvider;
    }

    public void setOauthProvider(String oauthProvider) {
        this.oauthProvider = oauthProvider;
    }

    public String getOauthUserId() {
        return oauthUserId;
    }

    public void setOauthUserId(String oauthUserId) {
        this.oauthUserId = oauthUserId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public void setProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    public Integer getFollowersCount() {
        return followersCount;
    }

    public void setFollowersCount(Integer followersCount) {
        this.followersCount = followersCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getDefaultWalletId() {
        return defaultWalletId;
    }

    public void setDefaultWalletId(String defaultWalletId) {
        this.defaultWalletId = defaultWalletId;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public LocalDateTime getBlockedAt() {
        return blockedAt;
    }

    public void setBlockedAt(LocalDateTime blockedAt) {
        this.blockedAt = blockedAt;
    }

    public String getBlockedByRequestId() {
        return blockedByRequestId;
    }

    public void setBlockedByRequestId(String blockedByRequestId) {
        this.blockedByRequestId = blockedByRequestId;
    }

    public String getBanReason() { return banReason; }
    public void setBanReason(String banReason) { this.banReason = banReason; }

    public LocalDateTime getBanExpiresAt() { return banExpiresAt; }
    public void setBanExpiresAt(LocalDateTime banExpiresAt) { this.banExpiresAt = banExpiresAt; }

    public String getBanIssuedBy() { return banIssuedBy; }
    public void setBanIssuedBy(String banIssuedBy) { this.banIssuedBy = banIssuedBy; }

    public String getBanType() { return banType; }
    public void setBanType(String banType) { this.banType = banType; }

    public Integer getStrikeCount() { return strikeCount; }
    public void setStrikeCount(Integer strikeCount) { this.strikeCount = strikeCount; }

    public LocalDateTime getLastStrikeAt() { return lastStrikeAt; }
    public void setLastStrikeAt(LocalDateTime lastStrikeAt) { this.lastStrikeAt = lastStrikeAt; }

    /**
     * True when the user is currently barred from the platform. A temporary
     * ban whose {@code banExpiresAt} is in the past returns {@code false} so
     * callers (login, refresh) treat the ban as auto-lifted; the persisted
     * flag is cleared lazily by {@code AuthService}.
     */
    public boolean isCurrentlyBanned() {
        if (!blocked) return false;
        if (banExpiresAt == null) return true;
        return banExpiresAt.isAfter(LocalDateTime.now());
    }

    public String getTempUUID() {
        return tempUUID;
    }

    public void setTempUUID(String tempUUID) {
        this.tempUUID = tempUUID;
    }

    public Instant getTempUUIDCreatedAt() {
        return tempUUIDCreatedAt;
    }

    public void setTempUUIDCreatedAt(Instant tempUUIDCreatedAt) {
        this.tempUUIDCreatedAt = tempUUIDCreatedAt;
    }

    public List<String> getContentLanguages() {
        return contentLanguages;
    }

    public void setContentLanguages(List<String> contentLanguages) {
        this.contentLanguages = contentLanguages;
    }

    public Boolean getIncludeMulti() {
        return includeMulti;
    }

    public void setIncludeMulti(Boolean includeMulti) {
        this.includeMulti = includeMulti;
    }

    public Boolean getShowAllLanguages() {
        return showAllLanguages;
    }

    public void setShowAllLanguages(Boolean showAllLanguages) {
        this.showAllLanguages = showAllLanguages;
    }
}
