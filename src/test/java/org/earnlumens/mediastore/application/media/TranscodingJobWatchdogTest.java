package org.earnlumens.mediastore.application.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TranscodingJobWatchdog}.
 * Verifies the watchdog delegates to {@link TranscodingJobService}
 * and catches exceptions gracefully.
 */
class TranscodingJobWatchdogTest {

    private TranscodingJobService jobService;
    private TranscodingJobWatchdog watchdog;

    @BeforeEach
    void setUp() {
        jobService = mock(TranscodingJobService.class);
        watchdog = new TranscodingJobWatchdog(jobService);
    }

    @Test
    void run_delegatesToService() {
        when(jobService.recoverStaleJobs()).thenReturn(3);

        watchdog.run();

        verify(jobService).recoverStaleJobs();
    }

    @Test
    void run_serviceReturnsZero_noError() {
        when(jobService.recoverStaleJobs()).thenReturn(0);

        watchdog.run();

        verify(jobService).recoverStaleJobs();
    }

    @Test
    void run_serviceThrows_doesNotPropagate() {
        when(jobService.recoverStaleJobs())
                .thenThrow(new RuntimeException("DB down"));

        // Should NOT throw — watchdog swallows exceptions
        watchdog.run();

        verify(jobService).recoverStaleJobs();
    }
}
