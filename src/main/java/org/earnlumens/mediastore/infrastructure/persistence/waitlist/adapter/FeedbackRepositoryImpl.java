package org.earnlumens.mediastore.infrastructure.persistence.waitlist.adapter;

import org.earnlumens.mediastore.domain.waitlist.model.Feedback;
import org.earnlumens.mediastore.domain.waitlist.repository.FeedbackRepository;
import org.earnlumens.mediastore.infrastructure.persistence.waitlist.entity.FeedbackEntity;
import org.earnlumens.mediastore.infrastructure.persistence.waitlist.mapper.FeedbackMapper;
import org.earnlumens.mediastore.infrastructure.persistence.waitlist.repository.FeedbackMongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepositoryImpl implements FeedbackRepository {

    private final FeedbackMongoRepository feedbackMongoRepository;
    private final FeedbackMapper feedbackMapper;

    public FeedbackRepositoryImpl(FeedbackMongoRepository feedbackMongoRepository, FeedbackMapper feedbackMapper) {
        this.feedbackMongoRepository = feedbackMongoRepository;
        this.feedbackMapper = feedbackMapper;
    }

    @Override
    public Feedback save(Feedback feedback) {
        FeedbackEntity feedbackEntity = feedbackMapper.toFeedbackEntity(feedback);
        return feedbackMapper.toFeedback(feedbackMongoRepository.save(feedbackEntity));
    }
}
