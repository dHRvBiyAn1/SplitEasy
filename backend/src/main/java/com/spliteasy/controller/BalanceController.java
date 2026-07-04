package com.spliteasy.controller;

import com.spliteasy.dto.GroupBalancesResponse;
import com.spliteasy.service.BalanceService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups/{groupId}/balances")
public class BalanceController {

    private final BalanceService balanceService;

    public BalanceController(BalanceService balanceService) {
        this.balanceService = balanceService;
    }

    @GetMapping
    public GroupBalancesResponse getBalances(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID groupId) {
        return balanceService.computeBalances(UUID.fromString(jwt.getSubject()), groupId);
    }
}
