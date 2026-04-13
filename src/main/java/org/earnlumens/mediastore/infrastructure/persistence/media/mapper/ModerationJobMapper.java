package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.ModerationDecision;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ModerationJobEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface ModerationJobMapper {

    @Mapping(target = "status", source = "status", qualifiedByName = "stringToModerationJobStatus")
    @Mapping(target = "entryType", source = "entryType", qualifiedByName = "stringToEntryType")
    @Mapping(target = "decision", source = "decision", qualifiedByName = "stringToModerationDecision")
    ModerationJob toModel(ModerationJobEntity entity);

    @Mapping(target = "status", source = "status", qualifiedByName = "moderationJobStatusToString")
    @Mapping(target = "entryType", source = "entryType", qualifiedByName = "entryTypeToString")
    @Mapping(target = "decision", source = "decision", qualifiedByName = "moderationDecisionToString")
    ModerationJobEntity toEntity(ModerationJob model);

    @Named("stringToModerationJobStatus")
    default ModerationJobStatus stringToModerationJobStatus(String value) {
        return value == null ? null : ModerationJobStatus.valueOf(value);
    }

    @Named("moderationJobStatusToString")
    default String moderationJobStatusToString(ModerationJobStatus value) {
        return value == null ? null : value.name();
    }

    @Named("stringToEntryType")
    default EntryType stringToEntryType(String value) {
        return value == null ? null : EntryType.valueOf(value);
    }

    @Named("entryTypeToString")
    default String entryTypeToString(EntryType value) {
        return value == null ? null : value.name();
    }

    @Named("stringToModerationDecision")
    default ModerationDecision stringToModerationDecision(String value) {
        return value == null ? null : ModerationDecision.valueOf(value);
    }

    @Named("moderationDecisionToString")
    default String moderationDecisionToString(ModerationDecision value) {
        return value == null ? null : value.name();
    }
}
