package com.spliteasy.controller;

import com.spliteasy.dto.balance.SimplifiedDebtsResponse;

import lombok.RequiredArgsConstructor;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.service.DebtSimplificationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only: suggested transfers that settle the group, derived from current balances. */
@RestController
@RequestMapping("/api/groups/{groupId}/debt-simplification")
@RequiredArgsConstructor
public class DebtSimplificationController {

    private final DebtSimplificationService debtSimplificationService;


    @GetMapping
    public SimplifiedDebtsResponse getSimplifiedDebts(@CurrentUserId UUID userId, @PathVariable UUID groupId) {
        return debtSimplificationService.simplify(userId, groupId);
    }
}
