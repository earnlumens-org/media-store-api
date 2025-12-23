package org.earnlumens.mediastore.domain.user.model;

import java.time.Instant;
import java.time.LocalDateTime;

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
    private boolean blocked;
    private LocalDateTime blockedAt;
    private String blockedByRequestId;
    private String tempUUID;
    private Instant tempUUIDCreatedAt;

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
}
