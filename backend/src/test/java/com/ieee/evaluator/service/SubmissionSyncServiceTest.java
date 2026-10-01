package com.ieee.evaluator.service;

import com.google.api.services.drive.Drive;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.ieee.evaluator.model.DriveFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionSyncServiceTest {

        @Mock(answer = Answers.RETURNS_DEEP_STUBS)
        private Sheets sheetsService;
        @Mock
        private GoogleSheetsService configLoader;
        @Mock
        private DynamicConfigService configService;
        @Mock
        private Drive driveService;
        @Mock
        private GoogleDriveMetadataService metadataService;

        private SubmissionSyncService service;

        @BeforeEach
        void setUp() {
                service = new SubmissionSyncService(
                                sheetsService, configLoader, configService, driveService, metadataService);
        }

    @Test
    void extractsIdsFromDriveLinkVariants() throws Exception {
        Method extractor = SubmissionSyncService.class.getDeclaredMethod("extractIdFromUrl", String.class);
        extractor.setAccessible(true);

        assertEquals("short_valid_id", extractor.invoke(service,
                "https://drive.google.com/open?id=short_valid_id"));
        assertEquals("document_id_123", extractor.invoke(service,
                "https://docs.google.com/document/d/document_id_123/edit?usp=sharing"));
        assertEquals("folder_id_123", extractor.invoke(service,
                "https://drive.google.com/drive/u/0/folders/folder_id_123"));
    }

    @Test
    void keepsSubmissionWhenDriveMetadataReturnsForbidden() throws Exception {
        when(configLoader.getDeliverableConfigs()).thenReturn(Map.of());
        when(configService.getValue("GOOGLE_SHEET_ID")).thenReturn("sheet-id");
        when(configService.getValue("GOOGLE_RESPONSES_RANGE")).thenReturn("A:Z");
        when(configService.getValue("COL_INDEX_TIMESTAMP")).thenReturn("0");
        when(configService.getValue("COL_INDEX_NAME")).thenReturn("1");
        when(configService.getValue("COL_INDEX_SECTION")).thenReturn("2");
        when(configService.getValue("COL_INDEX_TEAM")).thenReturn("3");
        when(configService.getValue("COL_INDEX_SRS")).thenReturn("4");
        when(configService.getValue("COL_INDEX_SDD")).thenReturn("-1");
        when(configService.getValue("COL_INDEX_SPMP")).thenReturn("-1");
        when(configService.getValue("COL_INDEX_STD")).thenReturn("-1");
        when(configService.getValue("COL_INDEX_PROPOSAL")).thenReturn("-1");
        when(configService.getValue("COL_INDEX_GITHUB")).thenReturn("-1");
        when(sheetsService.spreadsheets().values().get("sheet-id", "A:Z").execute())
                .thenReturn(new ValueRange().setValues(List.of(List.of(
                        "9/26/2026 10:00:00", "Student", "Section", "team",
                        "https://drive.google.com/file/d/abcdefghijklmnopqrstuvwxyz123456789/edit"))));
        when(metadataService.getMimeType("abcdefghijklmnopqrstuvwxyz123456789"))
                .thenThrow(new GoogleDriveMetadataService.MetadataLookupException(403, "Forbidden"));

        List<DriveFile> submissions = service.getLatestSubmissions();

        assertEquals(1, submissions.size());
        assertTrue(submissions.get(0).getName().contains("[SRS]"));
        assertEquals("application/vnd.google-apps.document", submissions.get(0).getMimeType());
    }
}