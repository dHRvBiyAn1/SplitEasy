package com.spliteasy.controller;

import com.spliteasy.dto.dashboard.DashboardResponse;

import lombok.RequiredArgsConstructor;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.service.DashboardService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;


    @GetMapping
    public DashboardResponse getDashboard(@CurrentUserId UUID userId) {
        return dashboardService.getDashboard(userId);
    }
}
