package com.spliteasy.controller;

import com.spliteasy.dto.balance.GroupBalancesResponse;
import com.spliteasy.dto.balance.PersonBalance;

import lombok.RequiredArgsConstructor;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.service.BalanceService;
import com.spliteasy.service.PairwiseBalanceService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups/{groupId}/balances")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceService balanceService;
    private final PairwiseBalanceService pairwiseService;


    @GetMapping
    public GroupBalancesResponse getBalances(@CurrentUserId UUID userId, @PathVariable UUID groupId) {
        return balanceService.computeBalances(userId, groupId);
    }

    /** Pairwise "who owes whom, to me" for the group balance-pills (positive = they owe you). */
    @GetMapping("/mine")
    public List<PersonBalance> getMyBalances(@CurrentUserId UUID userId, @PathVariable UUID groupId) {
        return pairwiseService.forGroup(userId, groupId);
    }
}
