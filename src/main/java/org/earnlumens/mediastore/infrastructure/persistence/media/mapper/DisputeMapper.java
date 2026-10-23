package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Dispute;
import org.earnlumens.mediastore.domain.media.model.DisputeStatus;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.DisputeEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface DisputeMapper {

    @Mapping(target = "status", source = "status", qualifiedByName = "stringToDisputeStatus")
    Dispute toModel(DisputeEntity entity);

    @Mapping(target = "status", source = "status", qualifiedByName = "disputeStatusToString")
    DisputeEntity toEntity(Dispute model);

    @Named("stringToDisputeStatus")
    default DisputeStatus stringToDisputeStatus(String v) { return v == null ? null : DisputeStatus.valueOf(v); }

    @Named("disputeStatusToString")
    default String disputeStatusToString(DisputeStatus v) { return v == null ? null : v.name(); }
}
