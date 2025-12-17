package org.earnlumens.mediastore.infrastructure.persistence.waitlist.mapper;

import org.earnlumens.mediastore.domain.waitlist.model.Founder;
import org.earnlumens.mediastore.infrastructure.persistence.waitlist.entity.FounderEntity;
import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FounderMapper {

    Founder toFounder(FounderEntity founderEntity);

    @InheritInverseConfiguration
    FounderEntity toFounderEntity(Founder founder);
}
