package com.spliteasy.controller;

import com.spliteasy.dto.common.HealthResponse;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight liveness endpoint the frontend polls to show backend status. */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String serviceName;

    public HealthController(@Value("${spring.application.name}") String serviceName) {
        this.serviceName = serviceName;
    }

    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("UP", serviceName, Instant.now());
    }
}
