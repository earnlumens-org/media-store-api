package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.TranscodingJobEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface TranscodingJobMapper {

    @Mapping(target = "status", source = "status", qualifiedByName = "stringToTranscodingJobStatus")
    TranscodingJob toModel(TranscodingJobEntity entity);

    @Mapping(target = "status", source = "status", qualifiedByName = "transcodingJobStatusToString")
    TranscodingJobEntity toEntity(TranscodingJob model);

    @Named("stringToTranscodingJobStatus")
    default TranscodingJobStatus stringToTranscodingJobStatus(String value) {
        return value == null ? null : TranscodingJobStatus.valueOf(value);
    }

    @Named("transcodingJobStatusToString")
    default String transcodingJobStatusToString(TranscodingJobStatus value) {
        return value == null ? null : value.name();
    }
}
