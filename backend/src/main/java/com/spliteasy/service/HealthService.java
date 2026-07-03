package com.spliteasy.service;

import com.spliteasy.dto.HealthResponse;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    public HealthResponse check() {
        return new HealthResponse("UP", "spliteasy-backend", Instant.now());
    }
}
