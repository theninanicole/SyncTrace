package com.ieee.evaluator.service;

import com.google.api.services.drive.Drive;
import com.google.api.services.sheets.v4.Sheets;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubmissionSyncServiceTest {

    @Test
    void extractsIdsFromDriveLinkVariants() throws Exception {
        SubmissionSyncService service = new SubmissionSyncService(
                null, null, null, null);
        Method extractor = SubmissionSyncService.class.getDeclaredMethod("extractIdFromUrl", String.class);
        extractor.setAccessible(true);

        assertEquals("short_valid_id", extractor.invoke(service,
                "https://drive.google.com/open?id=short_valid_id"));
        assertEquals("document_id_123", extractor.invoke(service,
                "https://docs.google.com/document/d/document_id_123/edit?usp=sharing"));
        assertEquals("folder_id_123", extractor.invoke(service,
                "https://drive.google.com/drive/u/0/folders/folder_id_123"));
    }
}