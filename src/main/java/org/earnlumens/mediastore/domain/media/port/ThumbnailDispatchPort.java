package org.earnlumens.mediastore.domain.media.port;

import org.earnlumens.mediastore.domain.media.model.ThumbnailJob;

/**
 * Port for dispatching thumbnail-processing jobs to an external worker.
 * The infrastructure layer provides the actual implementation
 * (Google Cloud Run Jobs API).
 */
public interface ThumbnailDispatchPort {

    /**
     * Dispatches a thumbnail job to the external worker.
     *
     * @param job the job to dispatch (must be in PENDING status)
     * @throws RuntimeException if the dispatch fails
     */
    void dispatch(ThumbnailJob job);
}
