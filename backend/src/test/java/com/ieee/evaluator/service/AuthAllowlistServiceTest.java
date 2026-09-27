package com.ieee.evaluator.service;

import com.ieee.evaluator.model.StudentTrackerRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit tests (no Spring context, no real Google Sheets calls). */
class AuthAllowlistServiceTest {

    private static final List<List<Object>> TEACHERS = List.of(List.of("teacher@cit.edu", "Teacher One"));

    @Test
    void concurrentFirstRequestsReadTheSheetOnlyOnce() throws Exception {
        GoogleSheetsService sheets = mock(GoogleSheetsService.class);
        AtomicInteger reads = new AtomicInteger();
        when(sheets.getSheetData(anyString())).thenAnswer(invocation -> {
            reads.incrementAndGet();
            Thread.sleep(150); // a slow Sheets call, while other requests arrive
            return TEACHERS;
        });
        AuthAllowlistService service = new AuthAllowlistService(sheets);

        int requests = 10;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<StudentTrackerRecord>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.verifyUser("teacher@cit.edu");
            }));
        }
        start.countDown();
        for (Future<StudentTrackerRecord> result : results) {
            StudentTrackerRecord record = result.get(5, TimeUnit.SECONDS);
            assertNotNull(record, "every concurrent request must be recognised");
            assertEquals("TEACHER", record.getRole());
        }
        pool.shutdownNow();

        assertEquals(1, reads.get(), "the Teachers sheet is read once, not once per request");
    }

    @Test
    void aFailedSheetReadIsRetriedInsteadOfDenyingTheRequest() throws Exception {
        GoogleSheetsService sheets = mock(GoogleSheetsService.class);
        when(sheets.getSheetData(anyString()))
            .thenThrow(new RuntimeException("429 Too Many Requests"))
            .thenReturn(TEACHERS);
        AuthAllowlistService service = new AuthAllowlistService(sheets);

        StudentTrackerRecord record = service.verifyUser("teacher@cit.edu");

        assertNotNull(record);
        assertEquals("TEACHER", record.getRole());
    }
}
