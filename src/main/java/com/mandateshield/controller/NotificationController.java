package com.mandateshield.controller;

import com.mandateshield.dto.UpcomingDebitAlert;
import com.mandateshield.service.ExecutiveDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final ExecutiveDashboardService dashboardService;

    @GetMapping("/upcoming-debits")
    public ResponseEntity<List<UpcomingDebitAlert>> getUpcomingDebits(@AuthenticationPrincipal Long userId) {
        var dashboard = dashboardService.getDashboard(userId);
        return ResponseEntity.ok(dashboard.upcomingDebitAlerts());
    }
}
