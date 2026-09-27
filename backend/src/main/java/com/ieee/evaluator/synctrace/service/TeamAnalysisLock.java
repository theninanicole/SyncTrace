package com.ieee.evaluator.synctrace.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Serializes gap detection per team. Detection replaces a team's findings (delete + insert),
 * so two overlapping runs — e.g. the mapping page saving several goals in parallel — would
 * otherwise both insert and leave duplicate findings behind. Callers must invoke the
 * transactional service method inside {@link #run} so its transaction commits before the
 * lock is released.
 */
@Component
public class TeamAnalysisLock {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public <T> T run(String teamCode, Supplier<T> action) {
        String key = teamCode == null ? "" : teamCode.trim().toUpperCase(Locale.ROOT);
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
}
