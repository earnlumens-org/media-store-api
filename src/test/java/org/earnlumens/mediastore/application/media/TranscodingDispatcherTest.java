package org.earnlumens.mediastore.application.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TranscodingDispatcher}.
 * Verifies the dispatcher delegates to {@link TranscodingJobService}
 * and catches exceptions gracefully.
 */
class TranscodingDispatcherTest {

    private TranscodingJobService jobService;
    private TranscodingDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        jobService = mock(TranscodingJobService.class);
        dispatcher = new TranscodingDispatcher(jobService);
    }

    @Test
    void run_delegatesToService() {
        when(jobService.dispatchPendingJobs()).thenReturn(3);

        dispatcher.run();

        verify(jobService).dispatchPendingJobs();
    }

    @Test
    void run_serviceReturnsZero_noError() {
        when(jobService.dispatchPendingJobs()).thenReturn(0);

        dispatcher.run();

        verify(jobService).dispatchPendingJobs();
    }

    @Test
    void run_serviceThrows_doesNotPropagate() {
        when(jobService.dispatchPendingJobs())
                .thenThrow(new RuntimeException("Cloud Run error"));

        // Should NOT throw — dispatcher swallows exceptions
        dispatcher.run();

        verify(jobService).dispatchPendingJobs();
    }
}
