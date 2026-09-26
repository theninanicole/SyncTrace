package com.ieee.evaluator.service;

import com.ieee.evaluator.model.StudentTrackerRecord;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthAllowlistService {

    // Every authenticated request is checked against the allowlist, so reading the sheets
    // each time exhausts the Google Sheets read quota; once reads fail, every request is
    // treated as unauthenticated (403). Sheet contents are cached briefly instead, and the
    // last good copy is used if a refresh fails.
    private static final long CACHE_TTL_MILLIS = 60_000;

    private record CachedRange(List<List<Object>> values, long fetchedAt) {}

    private final GoogleSheetsService sheetsService;
    private final Map<String, CachedRange> rangeCache = new ConcurrentHashMap<>();

    public AuthAllowlistService(GoogleSheetsService sheetsService) {
        this.sheetsService = sheetsService;
    }

    private List<List<Object>> readRange(String range) throws Exception {
        CachedRange cached = rangeCache.get(range);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < CACHE_TTL_MILLIS) {
            return cached.values();
        }
        try {
            List<List<Object>> values = sheetsService.getSheetData(range);
            rangeCache.put(range, new CachedRange(values, System.currentTimeMillis()));
            return values;
        } catch (Exception e) {
            if (cached != null) return cached.values();
            throw e;
        }
    }

    public StudentTrackerRecord verifyUser(String googleEmail) throws Exception {
        
        if (googleEmail == null || googleEmail.trim().isEmpty()) {
            return null;
        }

        String normalizedEmail = googleEmail.trim();

        // 1. VIP CHECK: Check the "Teachers" sheet
        String teachersRange = "Teachers!A2:B";
        List<List<Object>> teacherValues = readRange(teachersRange);
        
        if (teacherValues != null && !teacherValues.isEmpty()) {
            for (List<Object> row : teacherValues) {
                if (row == null || row.isEmpty()) continue;
                
                String sheetEmail = row.size() > 0 ? row.get(0).toString().trim() : "";
                
                if (sheetEmail.equalsIgnoreCase(normalizedEmail)) {
                    // Strictly use the name from the Sheet (Column B)
                    String teacherName = row.size() > 1 ? row.get(1).toString().trim() : "Unknown Teacher";
                    
                    return new StudentTrackerRecord(
                            teacherName,
                            "N/A", 
                            "N/A", 
                            "TEACHER"
                    );
                }
            }
        }

        // 2. STANDARD CHECK: Check the "Students" sheet
        String studentsRange = "Students!A2:D";
        List<List<Object>> studentValues = readRange(studentsRange);
        
        if (studentValues != null && !studentValues.isEmpty()) {
            for (List<Object> row : studentValues) {
                if (row == null || row.isEmpty()) continue;
                
                String sheetEmail = row.size() > 0 ? row.get(0).toString().trim() : "";
                
                if (sheetEmail.equalsIgnoreCase(normalizedEmail)) {
                    // Strictly use the name from the Sheet (Column B)
                    String studentName = row.size() > 1 ? row.get(1).toString().trim() : "Unknown Student";
                    String section = row.size() > 2 ? row.get(2).toString().trim() : "";
                    String teamCode = row.size() > 3 ? row.get(3).toString().trim() : "";
                    
                    return new StudentTrackerRecord(
                            studentName,
                            section,
                            teamCode,
                            "STUDENT"
                    );
                }
            }
        }
        
        // 3. Access Denied: Email not found in either sheet
        return null; 
    }
}