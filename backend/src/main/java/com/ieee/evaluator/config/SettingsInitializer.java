package com.ieee.evaluator.config;

import com.ieee.evaluator.model.SystemSetting;
import com.ieee.evaluator.repository.SystemSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class SettingsInitializer {

    private final SystemSettingRepository repository;

    public SettingsInitializer(SystemSettingRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeDefaultSettings() {
        log.info("Checking for required system settings...");
        
        // Google Sheets configuration
        upsertSetting("GOOGLE_SHEET_ID", "1q6cmg5f2WjM_6L7cMmWugZTaWYZMbm5i2jV2_hGq3Fc", "GENERAL", "Google Sheet ID for submission tracking");
        upsertSetting("GOOGLE_RESPONSES_RANGE", "Submissions!A2:K", "GENERAL", "Range for form responses");
        
        // Column indices
        upsertSetting("COL_INDEX_TIMESTAMP", "0", "GENERAL", "Column index for timestamp");
        upsertSetting("COL_INDEX_NAME", "2", "GENERAL", "Column index for student name");
        upsertSetting("COL_INDEX_SECTION", "3", "GENERAL", "Column index for section");
        upsertSetting("COL_INDEX_TEAM", "4", "GENERAL", "Column index for team code");
        upsertSetting("COL_INDEX_SRS", "5", "GENERAL", "Column index for SRS link");
        upsertSetting("COL_INDEX_SDD", "6", "GENERAL", "Column index for SDD link");
        upsertSetting("COL_INDEX_SPMP", "7", "GENERAL", "Column index for SPMP link");
        upsertSetting("COL_INDEX_STD", "8", "GENERAL", "Column index for STD link");
        upsertSetting("COL_INDEX_PROPOSAL", "9", "GENERAL", "Column index for Proposal link");
        upsertSetting("COL_INDEX_GITHUB", "10", "GENERAL", "Column index for GitHub link");
        
        log.info("System settings initialization complete");
    }

    private void upsertSetting(String key, String value, String category, String description) {
        repository.findById(key).ifPresentOrElse(
            existing -> {
                // Only update if the value is different
                if (!existing.getValue().equals(value)) {
                    log.info("Updating setting: {} = {}", key, value);
                    existing.setValue(value);
                    repository.save(existing);
                }
            },
            () -> {
                log.info("Creating default setting: {} = {}", key, value);
                SystemSetting setting = new SystemSetting();
                setting.setKey(key);
                setting.setValue(value);
                setting.setCategory(category);
                setting.setDescription(description);
                repository.save(setting);
            }
        );
    }
}
