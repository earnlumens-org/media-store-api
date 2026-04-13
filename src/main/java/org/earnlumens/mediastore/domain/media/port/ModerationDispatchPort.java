package org.earnlumens.mediastore.domain.media.port;

import org.earnlumens.mediastore.domain.media.model.ModerationJob;

/**
 * Port for dispatching moderation jobs to the external worker.
 * The infrastructure layer provides the actual implementation
 * (e.g., Google Cloud Run Jobs API).
 */
public interface ModerationDispatchPort {

    /**
     * Dispatches a moderation job to the external worker.
     *
     * @param job the job to dispatch (must be in PENDING status)
     * @throws RuntimeException if the dispatch fails
     */
    void dispatch(ModerationJob job);
}
