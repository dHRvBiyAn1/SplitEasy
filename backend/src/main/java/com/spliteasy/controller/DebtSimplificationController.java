package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.SimplifiedDebtsResponse;
import com.spliteasy.service.DebtSimplificationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only: suggested transfers that settle the group, derived from current balances. */
@RestController
@RequestMapping("/api/groups/{groupId}/debt-simplification")
public class DebtSimplificationController {

    private final DebtSimplificationService debtSimplificationService;

    public DebtSimplificationController(DebtSimplificationService debtSimplificationService) {
        this.debtSimplificationService = debtSimplificationService;
    }

    @GetMapping
    public SimplifiedDebtsResponse getSimplifiedDebts(@CurrentUserId UUID userId, @PathVariable UUID groupId) {
        return debtSimplificationService.simplify(userId, groupId);
    }
}
