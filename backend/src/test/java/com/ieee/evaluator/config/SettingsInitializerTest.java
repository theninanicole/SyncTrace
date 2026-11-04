package com.ieee.evaluator.config;

import com.ieee.evaluator.model.SystemSetting;
import com.ieee.evaluator.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingsInitializerTest {

    private final Map<String, SystemSetting> stored = new HashMap<>();

    private SystemSettingRepository repository() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0, String.class))));
        when(repository.save(any(SystemSetting.class))).thenAnswer(call -> {
            SystemSetting setting = call.getArgument(0);
            stored.put(setting.getKey(), setting);
            return setting;
        });
        return repository;
    }

    private void storeSheetId(String value) {
        SystemSetting setting = new SystemSetting();
        setting.setKey("GOOGLE_SHEET_ID");
        setting.setValue(value);
        stored.put("GOOGLE_SHEET_ID", setting);
    }

    @Test
    void configuredSpreadsheetReplacesStoredSheetId() {
        storeSheetId("old-sheet");

        new SettingsInitializer(repository(), "configured-sheet").initializeDefaultSettings();

        assertEquals("configured-sheet", stored.get("GOOGLE_SHEET_ID").getValue());
    }

    @Test
    void blankSpreadsheetKeepsStoredSheetId() {
        storeSheetId("old-sheet");

        new SettingsInitializer(repository(), " ").initializeDefaultSettings();

        assertEquals("old-sheet", stored.get("GOOGLE_SHEET_ID").getValue());
    }

    @Test
    void blankSpreadsheetDoesNotCreateSheetId() {
        new SettingsInitializer(repository(), "").initializeDefaultSettings();

        assertFalse(stored.containsKey("GOOGLE_SHEET_ID"));
    }
}
