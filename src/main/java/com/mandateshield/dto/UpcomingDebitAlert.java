package com.mandateshield.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record UpcomingDebitAlert(
        String subscriptionId,
        String merchantName,
        String category,
        BigDecimal amount,
        String currency,
        String paymentRail,
        Instant scheduledDebitDate,
        long hoursRemaining,
        boolean isWithin24Hours,
        String rbiComplianceNotice
) {}
