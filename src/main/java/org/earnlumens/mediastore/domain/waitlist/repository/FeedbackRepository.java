package org.earnlumens.mediastore.domain.waitlist.repository;

import org.earnlumens.mediastore.domain.waitlist.model.Feedback;

public interface FeedbackRepository {

    Feedback save(Feedback feedback);
}
