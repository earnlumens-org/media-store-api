package org.earnlumens.mediastore.application.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ModerationJobWatchdog}.
 */
class ModerationJobWatchdogTest {

    private ModerationJobService jobService;
    private ModerationJobWatchdog watchdog;

    @BeforeEach
    void setUp() {
        jobService = mock(ModerationJobService.class);
        watchdog = new ModerationJobWatchdog(jobService);
    }

    @Test
    void run_delegatesToService() {
        when(jobService.recoverStaleJobs()).thenReturn(2);

        watchdog.run();

        verify(jobService).recoverStaleJobs();
    }

    @Test
    void run_noStaleJobs_noError() {
        when(jobService.recoverStaleJobs()).thenReturn(0);

        watchdog.run();

        verify(jobService).recoverStaleJobs();
    }

    @Test
    void run_serviceThrows_doesNotPropagate() {
        when(jobService.recoverStaleJobs())
                .thenThrow(new RuntimeException("DB error"));

        watchdog.run();

        verify(jobService).recoverStaleJobs();
    }
}
