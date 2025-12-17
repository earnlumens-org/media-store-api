package org.earnlumens.mediastore.infrastructure.persistence.waitlist.mapper;

import org.earnlumens.mediastore.domain.waitlist.model.Feedback;
import org.earnlumens.mediastore.infrastructure.persistence.waitlist.entity.FeedbackEntity;
import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FeedbackMapper {

    Feedback toFeedback(FeedbackEntity feedbackEntity);

    @InheritInverseConfiguration
    FeedbackEntity toFeedbackEntity(Feedback feedback);
}
