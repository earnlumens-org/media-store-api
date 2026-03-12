package org.earnlumens.mediastore.domain.media.port;

import org.earnlumens.mediastore.domain.media.model.TranscodingJob;

/**
 * Port for dispatching transcoding jobs to an external worker.
 * The infrastructure layer provides the actual implementation
 * (e.g., Google Cloud Run Jobs API).
 */
public interface TranscodingDispatchPort {

    /**
     * Dispatches a transcoding job to the external worker.
     *
     * @param job the job to dispatch (must be in PENDING status)
     * @throws RuntimeException if the dispatch fails
     */
    void dispatch(TranscodingJob job);
}
