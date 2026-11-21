package com.mandateshield.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginRateLimiter {
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_SECONDS = 900; // 15 minutes

    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String key) {
        if (key == null || key.isBlank()) return false;
        AttemptRecord record = attempts.get(key.toLowerCase());
        if (record == null) return false;
        if (Instant.now().isAfter(record.expiresAt)) {
            attempts.remove(key.toLowerCase());
            return false;
        }
        return record.failedCount >= MAX_FAILED_ATTEMPTS;
    }

    public void recordFailure(String key) {
        if (key == null || key.isBlank()) return;
        String k = key.toLowerCase();
        Instant now = Instant.now();
        attempts.compute(k, (ignored, record) -> {
            if (record == null || now.isAfter(record.expiresAt)) {
                return new AttemptRecord(1, now.plusSeconds(LOCKOUT_DURATION_SECONDS));
            }
            return new AttemptRecord(record.failedCount + 1, now.plusSeconds(LOCKOUT_DURATION_SECONDS));
        });
    }

    public void recordSuccess(String key) {
        if (key != null) {
            attempts.remove(key.toLowerCase());
        }
    }

    private record AttemptRecord(int failedCount, Instant expiresAt) {}
}
