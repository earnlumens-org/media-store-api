package org.earnlumens.mediastore.infrastructure.persistence.space.mapper;

import org.earnlumens.mediastore.domain.space.Space;
import org.earnlumens.mediastore.domain.space.SpacePublishRule;
import org.earnlumens.mediastore.domain.space.SpaceStatus;
import org.earnlumens.mediastore.infrastructure.persistence.space.entity.SpaceEntity;
import org.springframework.stereotype.Component;

@Component
public class SpaceMapper {

    public Space toModel(SpaceEntity entity) {
        if (entity == null) return null;
        Space s = new Space();
        s.setId(entity.getId());
        s.setTenantId(entity.getTenantId());
        s.setKey(entity.getKey());
        s.setSystemSpace(entity.isSystemSpace());
        s.setStatus(parseStatus(entity.getStatus()));
        s.setShowInSidebar(entity.isShowInSidebar());
        s.setAllowPublishing(entity.isAllowPublishing());
        s.setSortOrder(entity.getSortOrder());
        s.setIcon(entity.getIcon());
        s.setWhoCanPublish(parseRule(entity.getWhoCanPublish()));
        s.setBaseName(entity.getBaseName());
        s.setTranslations(entity.getTranslations());
        s.setArchivedAt(entity.getArchivedAt());
        return s;
    }

    private static SpaceStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return SpaceStatus.ACTIVE;
        try {
            return SpaceStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            // Defensive: unknown value persisted by a future admin-api version
            // is treated as ARCHIVED so we don't accidentally distribute to it.
            return SpaceStatus.ARCHIVED;
        }
    }

    private static SpacePublishRule parseRule(String value) {
        if (value == null || value.isBlank()) return SpacePublishRule.ALL;
        try {
            return SpacePublishRule.valueOf(value);
        } catch (IllegalArgumentException ex) {
            // Unknown publish rule → fail-closed: only blue-credentialed users.
            return SpacePublishRule.VERIFIED_BLUE;
        }
    }
}
