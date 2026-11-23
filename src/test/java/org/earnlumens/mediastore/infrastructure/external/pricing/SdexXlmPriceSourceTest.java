package org.earnlumens.mediastore.infrastructure.external.pricing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.earnlumens.mediastore.infrastructure.stellar.HorizonServerPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.stellar.sdk.Server;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SDEX price source queries a Horizon node by its raw URL. A paid node
 * carries its key in that URL, which must never reach the logs.
 */
class SdexXlmPriceSourceTest {

    private static final String KEY = "pAthKey-0123456789abcdefABCDEF";
    private static final String KEYED_URL = "https://paid.example/v1/" + KEY;
    private static final String FALLBACK_URL = "https://h2.example";

    private HttpClient http;
    private HorizonServerPool pool;
    private SdexXlmPriceSource source;

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        pool = new HorizonServerPool(List.of(KEYED_URL, FALLBACK_URL), url -> mock(Server.class));
        source = new SdexXlmPriceSource(http, pool);
    }

    /** Runs {@code action} and returns everything logged by the source and by the pool. */
    private static String logged(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        List<Logger> loggers = List.of(
                (Logger) LoggerFactory.getLogger(SdexXlmPriceSource.class),
                (Logger) LoggerFactory.getLogger(HorizonServerPool.class));
        loggers.forEach(logger -> logger.addAppender(appender));
        try {
            action.run();
        } finally {
            loggers.forEach(logger -> logger.detachAppender(appender));
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    void failingNode_isQueriedByItsFullUrl_butLoggedWithoutItsKey() throws Exception {
        HttpResponse<String> response = mock();
        when(response.statusCode()).thenReturn(503);
        doReturn(response).when(http).send(any(), any());

        String logs = logged(() -> assertTrue(source.fetchPrice().isEmpty()));

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any());
        assertTrue(request.getValue().uri().toString().startsWith(KEYED_URL + "/order_book?"));
        assertFalse(logs.contains(KEY), logs);
        assertTrue(logs.contains("HTTP 503 from Horizon orderbook (https://paid.example)"), logs);
        assertTrue(logs.contains("Horizon node https://paid.example marked down"), logs);
        assertEquals(FALLBACK_URL, pool.nextReadUrl());
    }

    @Test
    void transportError_messageIsRedactedBeforeLogging() throws Exception {
        doThrow(new IOException("connection reset calling " + KEYED_URL + "/order_book"))
                .when(http).send(any(), any());

        String logs = logged(() -> assertTrue(source.fetchPrice().isEmpty()));

        assertFalse(logs.contains(KEY), logs);
        assertTrue(logs.contains("failed to fetch SDEX price: connection reset calling https://paid.example/order_book"),
                logs);
        assertEquals(FALLBACK_URL, pool.nextReadUrl());
    }
}
