package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.service.SystemSettingService;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context) for the GitHub ingestion safeguards:
 * oversized-file skipping, the per-run file cap, and stopping early on rate limits.
 */
@ExtendWith(MockitoExtension.class)
class GitHubIngestionServiceTest {

    @Mock private SystemSettingService configService;
    @Mock private TraceComponentRepository componentRepository;
    @Mock private ProgressEmitter progressEmitter;
    @Mock private RestTemplate restTemplate;

    private GitHubIngestionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String REPO_URL = "https://github.com/owner/repo/tree/main";
    private static final String TEAM_CODE = "2026-SEM1-IT01-01";

    @BeforeEach
    void setUp() {
        lenient().when(configService.getValueOrNull(anyString())).thenReturn(null);
        lenient().when(componentRepository.findByDocTypeAndNameIgnoreCase(any(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(componentRepository.save(any(TraceComponent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new GitHubIngestionService(configService, componentRepository, progressEmitter, restTemplate);
    }

    private ObjectNode blobNode(String path, long size) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("path", path);
        node.put("type", "blob");
        node.put("size", size);
        return node;
    }

    private String treeUrl() {
        return "https://api.github.com/repos/owner/repo/git/trees/main?recursive=1";
    }

    private String rawUrl(String path) {
        return "https://raw.githubusercontent.com/owner/repo/main/" + path;
    }

    private void stubRawFileFetch(String path) {
        when(restTemplate.exchange(eq(rawUrl(path)), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("content of " + path, HttpStatus.OK));
    }

    @Test
    void ingestRepositorySkipsFilesLargerThanSizeCap() throws Exception {
        ObjectNode tree = objectMapper.createObjectNode();
        ArrayNode entries = tree.putArray("tree");
        entries.add(blobNode("Small.java", 100));
        entries.add(blobNode("Huge.java", 1_000_000)); // over the 300 KB cap

        when(restTemplate.exchange(eq(treeUrl()), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(tree.toString(), HttpStatus.OK));
        stubRawFileFetch("Small.java");

        List<TraceComponent> result = service.ingestRepository(REPO_URL, TEAM_CODE, null);

        assertEquals(1, result.size());
        verify(restTemplate, never()).exchange(contains("Huge.java"), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    void ingestRepositoryCapsTotalFilesPerRun() throws Exception {
        ObjectNode tree = objectMapper.createObjectNode();
        ArrayNode entries = tree.putArray("tree");
        int fileCount = 510; // exceeds the 500-file cap
        for (int i = 0; i < fileCount; i++) {
            String path = "File" + i + ".java";
            entries.add(blobNode(path, 50));
            if (i < 500) stubRawFileFetch(path); // only the first 500 are ever fetched
        }

        when(restTemplate.exchange(eq(treeUrl()), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(tree.toString(), HttpStatus.OK));

        List<TraceComponent> result = service.ingestRepository(REPO_URL, TEAM_CODE, null);

        assertEquals(500, result.size());
        verify(restTemplate, never()).exchange(contains("File509.java"), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    void ingestRepositoryStopsEarlyWhenRateLimited() throws Exception {
        ObjectNode tree = objectMapper.createObjectNode();
        ArrayNode entries = tree.putArray("tree");
        entries.add(blobNode("First.java", 50));
        entries.add(blobNode("Second.java", 50));
        entries.add(blobNode("Third.java", 50));

        when(restTemplate.exchange(eq(treeUrl()), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(tree.toString(), HttpStatus.OK));
        stubRawFileFetch("First.java");
        when(restTemplate.exchange(eq(rawUrl("Second.java")), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("rate limited", HttpStatus.FORBIDDEN));

        List<TraceComponent> result = service.ingestRepository(REPO_URL, TEAM_CODE, null);

        assertEquals(1, result.size());
        verify(restTemplate, never()).exchange(contains("Third.java"), eq(HttpMethod.GET), any(), eq(String.class));
    }
}
